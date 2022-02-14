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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;

import com.esotericsoftware.tcpserver.UdpBroadcast.Subnets;

public abstract class UdpServer extends Retry {
	int port;
	private final byte[] receiveBuffer;
	DatagramSocket socket;

	public UdpServer (String category, String name) {
		this(category, name, 0, 1024);
	}

	public UdpServer (String category, String name, int port) {
		this(category, name, port, 1024);
	}

	public UdpServer (String category, String name, int port, int receiveBufferSize) {
		super(category, name);
		this.port = port;
		this.receiveBuffer = new byte[receiveBufferSize];
	}

	protected DatagramSocket bind () throws IOException {
		DatagramSocket socket = new DatagramSocket(null);
		socket.setReuseAddress(true);
		socket.bind(new InetSocketAddress(port));
		return socket;
	}

	protected void retry () {
		try {
			socket = bind();
		} catch (IOException ex) {
			if (ERROR) error(category, "Unable to start UDP server.", ex);
			failed();
			return;
		}
		success();
		try {
			if (INFO) info(category, "Listening on port: UDP " + port);
			byte[] receiveBuffer = this.receiveBuffer;
			DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
			while (running) {
				try {
					socket.receive(packet);
				} catch (SocketException ex) {
					if (!running) return;
					throw ex;
				}
				received(packet);
				packet.setLength(receiveBuffer.length);
			}
		} catch (Exception ex) {
			if (ERROR) error(category, "Unexpected UDP server error.", ex);
			closeQuietly(socket);
			failed();
		} finally {
			if (INFO) info(category, "Server stopped: UDP " + port);
		}
	}

	/** Called when a packet is received. */
	abstract protected void received (DatagramPacket packet) throws IOException;

	protected void stopped () {
		closeQuietly(socket);
	}

	public void send (byte[] buffer, InetAddress address, int port) throws IOException {
		socket.send(new DatagramPacket(buffer, buffer.length, address, port));
	}

	public int getPort () {
		return port;
	}

	public void setPort (int port) {
		this.port = port;
	}

	public DatagramSocket getSocket () {
		return socket;
	}

	static public void main (String[] args) throws Exception {
		TRACE();

		UdpServer server = new UdpServer("server", "test", 53333) {
			protected void received (DatagramPacket packet) {
				System.out.println(Util.toString(packet));
			}
		};
		server.start();

		BroadcastClient.find("client", new UdpBroadcast(Subnets.classC), 53333, 1000); // Will timeout.
	}
}
