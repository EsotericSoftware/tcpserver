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

import java.io.IOException;
import java.net.Socket;

public class TcpClient extends Retry {
	private String host;
	private int port;

	private volatile ClientConnection connection;
	private int reconnectDelay = 10 * 1000;
	private final Object waitForConnection = new Object();
	final Object waitForClose = new Object();

	public TcpClient (String category, String name) {
		this(category, name, "", 0);
	}

	public TcpClient (String category, String name, String host, int port) {
		super(category, name);
		this.host = host;
		this.port = port;
	}

	protected void retry () {
		Socket socket = null;
		try {
			socket = new Socket(host, port);
		} catch (IOException ex) {
			if (ERROR) error(category, "Unable to connect: " + host + ":" + port);
			failed();
			return;
		}

		synchronized (runLock) {
			if (!running) {
				closeQuietly(socket);
				return;
			}

			success();
			if (INFO) info(category, "Connected: " + socket.getInetAddress() + ":" + socket.getPort());

			try {
				connection = new ClientConnection(category, name, socket);
				connection.start();
			} catch (IOException ex) {
				if (ERROR) error(category, "Error configuring client connection.", ex);
				failed();
				return;
			}

			connected(connection);
			synchronized (waitForConnection) {
				waitForConnection.notifyAll();
			}
		}

		waitForClose(0);
		disconnected(connection);
	}

	protected void stopped () {
		ClientConnection connection = this.connection;
		if (connection != null) connection.close();
	}

	public boolean send (String message) {
		ClientConnection connection = this.connection;
		if (connection == null) {
			if (DEBUG) debug(category, "Unable to send, connection is closed: " + message);
			return false;
		}
		connection.send(message);
		return true;
	}

	public boolean send (String message, byte[] bytes) {
		return send(message, bytes, 0, bytes.length);
	}

	public boolean send (String message, byte[] bytes, int offset, int count) {
		ClientConnection connection = this.connection;
		if (connection == null) {
			if (DEBUG) debug(category, "Unable to send, connection is closed: " + message);
			return false;
		}
		connection.send(message, bytes, offset, count);
		return true;
	}

	public boolean sendBlocking (String message) {
		ClientConnection connection = this.connection;
		if (connection == null) {
			if (DEBUG) debug(category, "Unable to send, connection is closed: " + message);
			return false;
		}
		return connection.sendBlocking(message);
	}

	public boolean sendBlocking (String message, byte[] bytes) {
		return sendBlocking(message, bytes);
	}

	public boolean sendBlocking (String message, byte[] bytes, int offset, int count) {
		ClientConnection connection = this.connection;
		if (connection == null) {
			if (DEBUG) debug(category, "Unable to send, connection is closed: " + message);
			return false;
		}
		return connection.sendBlocking(message, bytes, offset, count);
	}

	public void connected (Connection connection) {
	}

	public void disconnected (Connection connection) {
	}

	public void receive (String event, String payload, byte[] bytes, int count) {
	}

	/** Returns the connection to the server, or null if not connected. */
	public Connection getConnection () {
		return connection;
	}

	public boolean isConnected () {
		return connection != null;
	}

	/** @param millis 0 to wait forever. */
	public boolean waitForConnection (long millis) {
		if (TRACE) trace(category, "Waiting for connection.");
		long until = System.currentTimeMillis() + millis;
		while (true) {
			synchronized (waitForConnection) {
				ClientConnection connection = TcpClient.this.connection;
				if (connection != null && !connection.closed) return true;
				long wait = 0;
				if (millis > 0) {
					wait = until - System.currentTimeMillis();
					if (wait < 0) return false;
				}
				try {
					waitForConnection.wait(wait);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	/** @param millis 0 to wait forever.
	 * @return true if the close happened. */
	public boolean waitForClose (long millis) {
		if (TRACE) trace(category, "Waiting for close.");
		long until = System.currentTimeMillis() + millis;
		while (true) {
			synchronized (waitForClose) {
				ClientConnection connection = TcpClient.this.connection;
				if (connection == null || connection.closed) return true;
				long wait = 0;
				if (millis > 0) {
					wait = until - System.currentTimeMillis();
					if (wait < 0) return false;
				}
				try {
					waitForClose.wait(wait);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	public String getHost () {
		return host;
	}

	public void setHost (String host) {
		this.host = host;
	}

	public int getPort () {
		return port;
	}

	public void setPort (int port) {
		this.port = port;
	}

	class ClientConnection extends Connection {
		public ClientConnection (String category, String name, Socket socket) throws IOException {
			super(category, name, socket);
		}

		public void receive (String event, String payload, byte[] bytes, int count) {
			TcpClient.this.receive(event, payload, bytes, count);
		}

		public void close () {
			super.close();
			synchronized (waitForClose) {
				waitForClose.notifyAll();
			}
		}
	}
}
