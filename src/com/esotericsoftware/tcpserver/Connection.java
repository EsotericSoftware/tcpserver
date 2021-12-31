/* Copyright (c) 2017-2021, Esoteric Software
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

/** A bidirectional connection between the client and server. All methods are thread safe. */
abstract public class Connection {
	final String category;
	private final String name;
	private final Socket socket;
	final Protocol protocol;
	final DataInputStream input;
	final DataOutputStream output;

	Thread writeThread;
	volatile boolean closed;

	Object userObject;

	public Connection (String category, String name, Socket socket, Protocol protocol) throws IOException {
		this.category = category;
		this.name = name;
		this.socket = socket;
		this.protocol = protocol;

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
					protocol.read(Connection.this);
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
					protocol.write(Connection.this);
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
		protocol.send(this, message);
	}

	/** @see #send(String, byte[], int, int) */
	public void send (String message, byte[] bytes) {
		protocol.send(this, message, bytes, 0, bytes.length);
	}

	/** Sends the string and bytes without waiting for the send to complete. The bytes are not copied so should not be modified
	 * during the wait.
	 * @param bytes May be null if count is 0. */
	public void send (String message, byte[] bytes, int offset, int count) {
		protocol.send(this, message, bytes, offset, count);
	}

	/** Sends the string, blocking until sending is complete.
	 * @return false if the connection is closed or the send failed (which closes the connection). */
	public boolean sendBlocking (String message) {
		return protocol.sendBlocking(this, message, null, 0, 0);
	}

	/** @see #sendBlocking(String, byte[], int, int) */
	public boolean sendBlocking (String message, byte[] bytes) {
		return protocol.sendBlocking(this, message, bytes, 0, bytes.length);
	}

	/** Sends the string and bytes, blocking until sending is complete.
	 * @param bytes May be null if count is 0.
	 * @return false if the connection is closed or the send failed (which closes the connection). */
	public boolean sendBlocking (String message, byte[] bytes, int offset, int count) {
		return protocol.sendBlocking(this, message, bytes, offset, count);
	}

	public Protocol getProtocol () {
		return protocol;
	}

	public DataInputStream getInput () {
		return input;
	}

	public DataOutputStream getOutput () {
		return output;
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

	public Object getUserObject () {
		return userObject;
	}

	public void setUserObject (Object userObject) {
		this.userObject = userObject;
	}
}
