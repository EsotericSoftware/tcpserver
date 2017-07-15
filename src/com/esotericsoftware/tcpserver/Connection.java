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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

abstract public class Connection {
	final String category;
	private final String name;
	private final Socket socket;
	BufferedReader input;
	OutputStreamWriter output;
	final ArrayBlockingQueue<String> sends = new ArrayBlockingQueue(1024, true);
	Thread writeThread;
	volatile boolean closed;

	public Connection (String category, String name, final Socket socket) throws IOException {
		this.category = category;
		this.name = name;
		this.socket = socket;

		try {
			input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			output = new OutputStreamWriter(socket.getOutputStream());
		} catch (IOException ex) {
			throw new IOException("Error opening socket streams.", ex);
		}
	}

	public void start () {
		new Thread(name + "Read") {
			public void run () {
				try {
					while (!closed) {
						String line = input.readLine();
						if (line == null || closed) break;
						int index = line.indexOf(" ");
						String event, payload;
						if (index != -1) {
							event = line.substring(0, index).trim();
							payload = line.substring(index + 1).trim();
						} else {
							event = line.trim();
							payload = "";
						}
						if (INFO) info(category, "Received: " + event + ", " + payload);
						try {
							receive(event, payload);
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
							String message = sends.take();
							if (closed) break;
							if (DEBUG) debug(category, "Sent: " + message);
							output.write(message + "\n");
							output.flush();
						} catch (InterruptedException ignored) {
						}
					}
				} catch (IOException ex) {
					if (ERROR && !closed) error(category, "Error writing to client.", ex);
				} finally {
					close();
					if (TRACE) trace(category, "Write thread stopped.");
				}
			}
		};
		writeThread.start();
	}

	public void send (String message) {
		sends.add(message);
	}

	abstract public void receive (String event, String payload);

	public void close () {
		if (INFO && !closed) info(category, "Client disconnected.");
		closed = true;
		if (writeThread != null) writeThread.interrupt();
		closeQuietly(output);
		closeQuietly(input);
		closeQuietly(socket);
	}

	static public void closeQuietly (Closeable closeable) {
		if (closeable == null) return;
		try {
			closeable.close();
		} catch (Throwable ignored) {
		}
	}
}
