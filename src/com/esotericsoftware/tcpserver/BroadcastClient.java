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
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Enumeration;

public class BroadcastClient extends Retry {
	private int port, timeoutMillis = 3000;
	private final byte[] receive = new byte[BroadcastServer.prefix.length];
	private final byte[] request = new byte[BroadcastServer.prefix.length];

	public BroadcastClient (String category, String name) {
		this(category, name, 0);
	}

	public BroadcastClient (String category, String name, int port) {
		super(category, name);
		this.port = port;
		setRetryDelays(6);
	}

	protected void retry () {
		DatagramPacket packet = find(port, timeoutMillis, requestBuffer(), receiveBuffer());
		if (running && packet != null) received(packet);
		failed(); // Always sleep.
	}

	/** Returns the buffer which is sent as a request. It must be at least large enough for {@link BroadcastServer#prefix}, which
	 * is written at the start. */
	protected byte[] requestBuffer () {
		return request;
	}

	/** Returns the buffer into which the received packet will be written. It must be at least large enough for
	 * {@link BroadcastServer#prefix}. */
	protected byte[] receiveBuffer () {
		return receive;
	}

	/** Called with the packet received from the server. The data will start with {@link BroadcastServer#prefix}. */
	protected void received (DatagramPacket packet) {
	}

	public int getPort () {
		return port;
	}

	public void setPort (int port) {
		this.port = port;
	}

	public int getTimeout () {
		return timeoutMillis;
	}

	public void setTimeout (int millis) {
		timeoutMillis = millis;
	}

	/** @return May be null. */
	static public DatagramPacket find (int port, int timeoutMillis) {
		byte[] request = new byte[BroadcastServer.prefix.length + 4];
		byte[] receive = new byte[BroadcastServer.prefix.length];
		return find(port, timeoutMillis, request, receive);
	}

	/** @return May be null. */
	static public DatagramPacket find (int port, int timeoutMillis, byte[] requestBuffer, byte[] receiveBuffer) {
		System.arraycopy(BroadcastServer.prefix, 0, requestBuffer, 0, BroadcastServer.prefix.length);
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
			for (Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements();) {
				NetworkInterface iface = ifaces.nextElement();
				for (Enumeration<InetAddress> addresses = iface.getInetAddresses(); addresses.hasMoreElements();) {
					byte[] ip = addresses.nextElement().getAddress();
					ip[3] = -1; // 255.255.255.0
					try {
						socket.send(new DatagramPacket(requestBuffer, requestBuffer.length, InetAddress.getByAddress(ip), port));
					} catch (Exception ignored) {
					}
					ip[2] = -1; // 255.255.0.0
					try {
						socket.send(new DatagramPacket(requestBuffer, requestBuffer.length, InetAddress.getByAddress(ip), port));
					} catch (Exception ignored) {
					}
				}
			}
			if (DEBUG) debug("broadcast", "Broadcasted on port: UDP " + port);

			socket.setSoTimeout(timeoutMillis);
			DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
			try {
				socket.receive(packet);
			} catch (SocketTimeoutException ex) {
				if (DEBUG) debug("broadcast", "Host discovery timed out.");
				return null;
			}
			int i = 0;
			for (int n = BroadcastServer.prefix.length; i < n; i++) {
				if (receiveBuffer[i] != BroadcastServer.prefix[i]) {
					if (DEBUG) {
						StringBuilder buffer = new StringBuilder();
						for (i = 0; i < n; i++)
							buffer.append(Integer.toHexString(receiveBuffer[i]) + " ");
						buffer.setLength(buffer.length() - 1);
						debug("broadcast", "Client received invalid packet, prefix: " + buffer);
					}
					return null;
				}
			}
			if (INFO) info("broadcast", "Discovered server: " + packet.getAddress());
			return packet;
		} catch (IOException ex) {
			if (ERROR) error("broadcast", "Host discovery failed.", ex);
			return null;
		} finally {
			closeQuietly(socket);
		}
	}
}
