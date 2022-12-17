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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

abstract public class TcpServer extends Retry {
	final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList();
	private int port;
	private ServerSocket server;

	public TcpServer (String category, String name) {
		this(category, name, 0);
	}

	public TcpServer (String category, String name, int port) {
		super(category, name);
		this.port = port;
	}

	protected void retry () {
		try {
			server = newProtocol().newServerSocket(port);
		} catch (Exception ex) {
			if (ERROR) error(category, "Unable to open TCP server socket.", ex);
			failed();
			return;
		}
		try {
			if (INFO) info(category, "Listening on port: TCP " + port);
			while (running) {
				Socket socket;
				try {
					socket = server.accept();
				} catch (SocketException ex) {
					if (!running) return; // Assume server socket was closed normally.
					throw ex;
				}
				success();
				try {
					ServerConnection connection = new ServerConnection(category, name, socket, newProtocol());
					connections.add(connection);
					newConnection(connection);
					if (INFO) info(category, "Client connected: " + socket.getInetAddress() + ":" + socket.getPort());
					connection.start();
					connected(connection);
				} catch (Exception ex) {
					if (ERROR) error(category, "Error configuring client connection.", ex);
				}
			}
		} catch (Exception ex) {
			if (ERROR) error(category, "Unexpected server error.", ex);
			closeQuietly(server);
			failed();
		} finally {
			if (INFO) info(category, "Server stopped: TCP " + port);
		}
	}

	protected Protocol newProtocol () {
		return new DefaultProtocol();
	}

	protected void stopped () {
		for (int i = connections.size() - 1; i >= 0; i--)
			connections.get(i).close();
		connections.clear();
		closeQuietly(server);
	}

	/** Called when a new connection has been created, before it is started. */
	public void newConnection (Connection connection) {
	}

	/** Called after a new connection has been started. */
	public void connected (Connection connection) {
	}

	public void disconnected (Connection connection) {
	}

	public List<Connection> getConnections () {
		return connections;
	}

	public void send (String message) {
		for (Connection connection : connections)
			connection.send(message);
	}

	public void send (String message, byte... bytes) {
		send(message, bytes, 0, bytes.length);
	}

	public void send (String message, byte[] bytes, int offset, int count) {
		for (Connection connection : connections)
			connection.send(message, bytes, offset, count);
	}

	public boolean sendBlocking (String message) {
		boolean success = true;
		for (Connection connection : connections)
			if (!connection.sendBlocking(message)) success = false;
		return success;
	}

	public boolean sendBlocking (String message, byte... bytes) {
		return sendBlocking(message, bytes, 0, bytes.length);
	}

	public boolean sendBlocking (String message, byte[] bytes, int offset, int count) {
		boolean success = true;
		for (Connection connection : connections)
			if (!connection.sendBlocking(message, bytes, offset, count)) success = false;
		return success;
	}

	/** Override to handle a connection receiving data. */
	public void receive (Connection connection, String event, String payload, byte[] bytes, int count) {
	}

	public int getPort () {
		return port;
	}

	public void setPort (int port) {
		this.port = port;
	}

	private class ServerConnection extends Connection {
		public ServerConnection (String category, String name, Socket socket, Protocol protocol) throws IOException {
			super(category, name, socket, protocol);
		}

		public boolean isValid () {
			if (connections.contains(this)) return true;
			return false;
		}

		public void receive (String event, String payload, byte[] bytes, int count) {
			TcpServer.this.receive(this, event, payload, bytes, count);
		}

		public void close () {
			boolean wasClosed = this.closed;
			super.close();
			if (!wasClosed) {
				disconnected(this);
				connections.remove(this);
			}
		}
	}

	static public void main (String[] args) throws Exception {
		TRACE();

		TcpServer server = new TcpServer("server", "TestServer", 4567) {
			public void receive (Connection connection, String event, String payload, byte[] bytes, int count) {
				System.out.println("Server received: " + event + ", " + payload + ", " + count);
				send("ok good");
			}
		};
		server.start();

		TcpClient client = new TcpClient("client", "TestClient", "localhost", 4567) {
			public void receive (String event, String payload, byte[] bytes, int count) {
				System.out.println("Client received: " + event + ", " + payload + ", " + count);
				getConnection().close();
			}
		};
		client.start();
		client.waitForConnection(0);
		client.sendBlocking("yay moo");
	}
}
