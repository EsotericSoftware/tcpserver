/* Copyright (c) 2017, Esoteric Software
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.esotericsoftware.tcpserver;

import static com.esotericsoftware.minlog.Log.*;
import static com.esotericsoftware.tcpserver.Util.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/** A bidirectional connection between the client and server. All methods are thread safe. */
abstract public class Connection {
	static private final byte[] empty = new byte[0];

	final String category;
	private final String name;
	private final Socket socket;
	final DataInputStream input;
	final DataOutputStream output;
	final Object outputLock = new Object();

	final ArrayBlockingQueue sends = new ArrayBlockingQueue(1024, true);
	Thread writeThread;
	volatile boolean closed;
	byte[] data = empty;

	public Connection (String category, String name, Socket socket) throws IOException {
		this.category = category;
		this.name = name;
		this.socket = socket;

		try {
			input = new DataInputStream(socket.getInputStream());
			output = new DataOutputStream(socket.getOutputStream());
		} catch (IOException ex) {
			throw new IOException("Error opening socket streams.", ex);
		}
	}

	void start () {
		new Thread(name + "Read") {
			public void run () {
				try {
					while (!closed) {
						String message = input.readUTF();
						if (message == null || closed) break;
						String event, payload;
						int index = message.indexOf(" ");
						if (index != -1) {
							event = message.substring(0, index).trim();
							payload = message.substring(index + 1).trim();
						} else {
							event = message.trim();
							payload = "";
						}

						int dataLength = readVarint(input);
						if (dataLength > 0) {
							if (closed) break;
							if (data.length < dataLength) data = new byte[dataLength];
							int offset = 0, remaining = dataLength;
							while (true) {
								int count = input.read(data, offset, remaining);
								if (count == -1 || closed) break;
								remaining -= count;
								if (remaining == 0) break;
								offset += count;
							}
						}

						if (TRACE) trace(category, "Received: " + event + ", " + payload + (dataLength > 0 ? ", " + dataLength : ""));
						try {
							receive(event, payload, data, dataLength);
						} catch (Throwable ex) {
							if (ERROR) error(category, "Error processing message: " + message, ex);
							break;
						}
					}
				} catch (EOFException ex) {
					if (TRACE) trace(category, "Connection has closed.", ex);
				} catch (IOException ex) {
					if (!closed) {
						if (ex.getMessage() != null && ex.getMessage().contains("Connection reset")) {
							if (TRACE) trace(category, "Client connection reset.", ex);
						} else {
							if (ERROR) error(category, "Error reading from connection.", ex);
						}
					}
				} finally {
					close();
					if (TRACE) trace(category, "Read thread stopped.");
				}
			}
		}.start();

		writeThread = new Thread(name + "Write") {
			public void run () {
				try {
					while (!closed) {
						try {
							Object object = sends.take();
							if (object instanceof String)
								sendBlocking((String)object, null, 0, 0);
							else {
								Send send = (Send)object;
								sendBlocking(send.message, send.bytes, send.offset, send.count);
							}
						} catch (InterruptedException ignored) {
						}
					}
				} finally {
					close();
					if (TRACE) trace(category, "Write thread stopped.");
				}
			}
		};
		writeThread.start();
	}

	/** Sends the string without waiting for the send to complete. */
	public void send (String message) {
		if (TRACE) trace(category, "Queued: " + message);
		sends.add(message);
	}

	/** @see #send(String, byte[], int, int) */
	public void send (String message, byte[] bytes) {
		send(message, bytes, 0, bytes.length);
	}

	/** Sends the string and bytes without waiting for the send to complete. The bytes are not copied so should not be modified
	 * during the wait.
	 * @param bytes May be null if count is 0. */
	public void send (String message, byte[] bytes, int offset, int count) {
		if (count != 0 && bytes == null) throw new IllegalArgumentException("bytes cannot be null when count != 0: " + count);
		if (TRACE) trace(category, "Queued: " + message + ", " + count);
		Send send = new Send();
		send.message = message;
		send.bytes = bytes;
		send.offset = offset;
		send.count = count;
		sends.add(send);
	}

	/** Sends the string, blocking until sending is complete.
	 * @return false if the connection is closed or the send failed (which closes the connection). */
	public boolean sendBlocking (String message) {
		return sendBlocking(message, null, 0, 0);
	}

	/** @see #sendBlocking(String, byte[], int, int) */
	public boolean sendBlocking (String message, byte[] bytes) {
		return sendBlocking(message, bytes, 0, bytes.length);
	}

	/** Sends the string and bytes, blocking until sending is complete.
	 * @param bytes May be null if count is 0.
	 * @return false if the connection is closed or the send failed (which closes the connection). */
	public boolean sendBlocking (String message, byte[] bytes, int offset, int count) {
		if (count != 0 && bytes == null) throw new IllegalArgumentException("bytes cannot be null when count != 0: " + count);
		if (closed) return false;
		try {
			synchronized (outputLock) {
				if (TRACE) trace(category, "Sent: " + message + (count > 0 ? ", " + count : ""));
				output.writeUTF(message);
				writeVarint(count, output);
				if (count != 0) output.write(bytes, offset, count);
				output.flush();
			}
			return true;
		} catch (IOException ex) {
			if (ERROR && !closed) error(category, "Error writing to connection: " + message, ex);
			close();
			return false;
		}
	}

	/** @param bytes May be null if count is 0. */
	abstract public void receive (String event, String payload, byte[] bytes, int count);

	public void close () {
		if (INFO && !closed) info(category, "Client disconnected.");
		closed = true;
		if (writeThread != null) writeThread.interrupt();
		closeQuietly(output);
		closeQuietly(input);
		closeQuietly(socket);
	}

	public boolean isClosed () {
		return closed;
	}

	static class Send {
		String message;
		byte[] bytes;
		int offset;
		int count;
	}
}
