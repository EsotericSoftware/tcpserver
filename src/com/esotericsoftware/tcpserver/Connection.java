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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/** A bidirectional connection between the client and server. All methods are thread safe. */
abstract public class Connection {
	static private final byte[] empty = new byte[0];

	final String category;
	private final String name;
	private final Socket socket;
	final InputStream input;
	final BufferedReader reader;
	final OutputStream output;
	final OutputStreamWriter writer;
	final Object outputLock = new Object();

	final ArrayBlockingQueue<String> sends = new ArrayBlockingQueue(1024, true);
	Thread writeThread;
	volatile boolean closed;
	byte[] data = empty;

	public Connection (String category, String name, final Socket socket) throws IOException {
		this.category = category;
		this.name = name;
		this.socket = socket;

		try {
			input = socket.getInputStream();
			reader = new BufferedReader(new InputStreamReader(input));
			output = socket.getOutputStream();
			writer = new OutputStreamWriter(output);
		} catch (IOException ex) {
			throw new IOException("Error opening socket streams.", ex);
		}
	}

	public void start () {
		close();

		new Thread(name + "Read") {
			public void run () {
				try {
					while (!closed) {
						String line = reader.readLine();
						if (line == null || closed) break;
						String event, payload;
						int index = line.indexOf(" ");
						if (index != -1) {
							event = line.substring(0, index).trim();
							payload = line.substring(index + 1).trim();
						} else {
							event = line.trim();
							payload = "";
						}

						int dataLength = readInt(input);
						if (dataLength < 0 || closed) break;
						if (data.length < dataLength) data = new byte[dataLength];
						int offset = 0, remaining = dataLength;
						while (true) {
							int count = input.read(data, offset, remaining);
							if (count == -1 || closed) break;
							remaining -= count;
							if (remaining == 0) break;
							offset += count;
						}

						if (INFO) info(category, "Received: " + event + ", " + payload + (dataLength > 0 ? ", " + dataLength : ""));
						try {
							receive(event, payload, data, dataLength);
						} catch (Throwable ex) {
							if (ERROR) error(category, "Error processing message: " + line, ex);
							break;
						}
					}
				} catch (IOException ex) {
					if (ERROR && !closed) error(category, "Error reading from client.", ex);
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
							sendBlocking(sends.take());
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

	/** Sends the string, blocking until sending is complete.
	 * @return false if the connection is closed or the send failed (which closes the connection). */
	public boolean sendBlocking (String message) {
		if (closed) return false;
		try {
			synchronized (outputLock) {
				if (DEBUG) debug(category, "Sent: " + message);
				writer.write(message + "\n");
				writer.flush();
				writeInt(0, output);
				output.flush();
			}
			return true;
		} catch (IOException ex) {
			if (ERROR && !closed) error(category, "Error writing to client.", ex);
			close();
			return false;
		}
	}

	/** @see #sendBlocking(String, byte[], int, int) */
	public boolean sendBlocking (String string, byte[] bytes) {
		return sendBlocking(string, bytes, 0, bytes.length);
	}

	/** Sends the string, blocking until sending is complete.
	 * @return false if the connection is closed or the send failed (which closes the connection). */
	public boolean sendBlocking (String message, byte[] bytes, int offset, int count) {
		if (closed) return false;
		try {
			synchronized (outputLock) {
				if (DEBUG) debug(category, "Sent: " + message + (count > 0 ? ", " + count : ""));
				writer.write(message + "\n");
				writer.flush();
				writeInt(count, output);
				output.write(bytes, offset, count);
				output.flush();
			}
			return true;
		} catch (IOException ex) {
			if (ERROR && !closed) error(category, "Error writing to client.", ex);
			close();
			return false;
		}
	}

	abstract public void receive (String event, String payload, byte[] bytes, int count);

	public void close () {
		if (INFO && !closed) info(category, "Client disconnected.");
		closed = true;
		if (writeThread != null) writeThread.interrupt();
		closeQuietly(writer);
		closeQuietly(reader);
		closeQuietly(socket);
	}
}
