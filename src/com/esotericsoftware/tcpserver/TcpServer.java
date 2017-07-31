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
import static com.esotericsoftware.tcpserver.Connection.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

abstract public class TcpServer extends Retry {
	final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList();
	private final int port;
	private ServerSocket server;

	public TcpServer (String category, String name, int port) {
		super(category, name);
		this.port = port;
	}

	protected void retry () {
		try {
			server = new ServerSocket(port);
			if (INFO) info(category, "Listening on port: " + port);
			while (running) {
				Socket socket = server.accept();
				success();
				try {
					ServerConnection connection = new ServerConnection(category, name, socket);
					connections.add(connection);
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
		}
	}

	protected void stopping () {
		closeQuietly(server);
	}

	public void connected (Connection connection) {
	}

	public void disconnected (Connection connection) {
	}

	public void send (String message) {
		for (Connection connection : connections)
			connection.send(message);
	}

	abstract public void receive (Connection connection, String event, String payload);

	public void close () {
		for (Connection connection : connections) {
			connection.close();
			disconnected(connection);
		}
		connections.clear();
	}

	private class ServerConnection extends Connection {
		public ServerConnection (String category, String name, Socket socket) throws IOException {
			super(category, name, socket);
		}

		public boolean isValid () {
			if (connections.contains(this)) return true;
			return false;
		}

		public void receive (String event, String payload) {
			TcpServer.this.receive(this, event, payload);
		}

		public void close () {
			super.close();
			connections.remove(this);
		}
	}

	static public void main (String[] args) throws Exception {
		TRACE();

		TcpServer server = new TcpServer("server", "TestServer", 4567) {
			public void receive (Connection connection, String event, String payload) {
				System.out.println("Server received: " + event + ", " + payload);
				send("ok good");
			}
		};
		server.start();

		TcpClient client = new TcpClient("client", "TestClient", "localhost", 4567) {
			public void receive (String event, String payload) {
				System.out.println("Client received: " + event + ", " + payload);
				getConnection().close();
			}
		};
		client.start();
		client.waitForConnection(0);
		client.send("yay moo");
	}
}
