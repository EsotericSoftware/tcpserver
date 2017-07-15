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

import java.io.IOException;
import java.net.Socket;

public abstract class TcpClient {
	volatile ClientConnection connection;
	int reconnectDelay = 10 * 1000;
	final Object waitForConnection = new Object();
	final Object waitForClose = new Object();

	public TcpClient (String category, String name, String host, int port) {
		new Thread(name) {
			public void run () {
				while (true) {
					Socket socket = null;
					try {
						socket = new Socket(host, port);
					} catch (IOException ex) {
						if (ERROR) error(category, "Unable to connect: " + host + ":" + port);
						sleep();
						continue;
					}
					if (INFO) info(category, "Connected: " + socket.getInetAddress() + ":" + socket.getPort());

					try {
						connection = new ClientConnection(category, name, socket);
						connection.start();
					} catch (IOException ex) {
						if (ERROR) error("server", "Error configuring client connection.", ex);
						sleep();
						continue;
					}

					synchronized (waitForConnection) {
						waitForConnection.notifyAll();
					}

					waitForClose(0);
				}
			}

			private void sleep () {
				try {
					sleep(reconnectDelay);
				} catch (InterruptedException ignored) {
				}
			}
		}.start();
	}

	public boolean send (String message) {
		ClientConnection connection = this.connection;
		if (connection == null) {
			if (DEBUG) debug("Unable to send, connection is closed: " + message);
			return false;
		}
		connection.sends.add(message);
		return true;
	}

	abstract public void receive (String event, String payload);

	/** @param millis 0 to wait forever. */
	public void waitForConnection (long millis) {
		long until = System.currentTimeMillis() + millis;
		while (true) {
			synchronized (waitForConnection) {
				ClientConnection connection = TcpClient.this.connection;
				if (connection != null && !connection.closed) return;
				long wait = 0;
				if (millis > 0) {
					wait = until - System.currentTimeMillis();
					if (wait < 0) return;
				}
				try {
					waitForConnection.wait(wait);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	/** @param millis 0 to wait forever. */
	public void waitForClose (long millis) {
		long until = System.currentTimeMillis() + millis;
		while (true) {
			synchronized (waitForClose) {
				ClientConnection connection = TcpClient.this.connection;
				if (connection == null || connection.closed) return;
				long wait = 0;
				if (millis > 0) {
					wait = until - System.currentTimeMillis();
					if (wait < 0) return;
				}
				try {
					waitForClose.wait(wait);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	public void setReconnectDelay (int reconnectDelay) {
		this.reconnectDelay = reconnectDelay;
	}

	class ClientConnection extends Connection {
		public ClientConnection (String category, String name, Socket socket) throws IOException {
			super(category, name, socket);
		}

		public void receive (String event, String payload) {
			TcpClient.this.receive(event, payload);
		}

		public void close () {
			super.close();
			synchronized (waitForClose) {
				waitForClose.notifyAll();
			}
		}
	}
}
