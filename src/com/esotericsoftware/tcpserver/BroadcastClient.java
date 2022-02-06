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
import java.net.SocketTimeoutException;

public class BroadcastClient extends Retry {
	private int port, timeoutMillis = 3000;
	private final UdpBroadcast broadcast;
	private final byte[] receive = new byte[BroadcastServer.prefix.length];
	private final byte[] request = new byte[BroadcastServer.prefix.length];

	public BroadcastClient (String category, String name, UdpBroadcast broadcast, int port) {
		super(category, name);
		this.broadcast = broadcast;
		this.port = port;
		setRetryDelays(6);
	}

	protected void retry () {
		DatagramPacket packet = find(category, broadcast, port, timeoutMillis, requestBuffer(), receiveBuffer());
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
	static public DatagramPacket find (String category, UdpBroadcast broadcast, int port, int timeoutMillis) {
		byte[] buffer = new byte[BroadcastServer.prefix.length];
		return find(category, broadcast, port, timeoutMillis, buffer, buffer);
	}

	/** @return May be null. */
	static public DatagramPacket find (String category, UdpBroadcast broadcast, int port, int timeoutMillis, byte[] requestBuffer,
		byte[] receiveBuffer) {

		System.arraycopy(BroadcastServer.prefix, 0, requestBuffer, 0, BroadcastServer.prefix.length);

		try {
			broadcast.bind();

			if (DEBUG) debug(category, "Broadcast to port: UDP " + port);
			broadcast.broadcast(port, requestBuffer);

			DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
			broadcast.getSocket().setSoTimeout(timeoutMillis);
			try {
				broadcast.getSocket().receive(packet);
			} catch (SocketTimeoutException ex) {
				if (DEBUG) debug(category, "Host discovery timed out.");
				return null;
			}

			if (!BroadcastServer.hasPrefix(packet)) {
				if (DEBUG) debug(category, "Client received invalid UDP packet, prefix: " + Util.toString(packet));
				return null;
			}

			if (INFO) info(category, "Discovered server: " + packet.getAddress());
			return packet;
		} catch (IOException ex) {
			if (ERROR) error(category, "Host discovery failed.", ex);
			return null;
		} finally {
			closeQuietly(broadcast);
		}
	}
}
