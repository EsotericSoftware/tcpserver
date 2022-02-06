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

import java.io.IOException;
import java.net.DatagramPacket;

import com.esotericsoftware.tcpserver.UdpBroadcast.Subnets;

public class BroadcastServer extends UdpServer {
	static public final byte[] prefix = new byte[] {62, 126, -17, 61, 127, -16, 63, 125, -18};

	public BroadcastServer (String category, String name) {
		super(category, name);
	}

	public BroadcastServer (String category, String name, int port) {
		super(category, name, port);
	}

	/** By default it must be at least large enough for {@link #prefix}. */
	protected byte[] receiveBuffer () {
		return new byte[prefix.length];
	}

	protected final void received (DatagramPacket packet) throws IOException {
		if (!isValid(packet)) return;

		if (DEBUG) debug(category, "Received broadcast: " + packet.getAddress() + ":" + packet.getPort());

		byte[] response = response(packet);
		if (response != null) socket.send(new DatagramPacket(response, response.length, packet.getAddress(), packet.getPort()));
	}

	/** Returns the buffer to send as a response, or null not to send a response. By default it must have {@link #prefix} at the
	 * start. */
	protected byte[] response (DatagramPacket packet) {
		return prefix;
	}

	/** True if the packet data is valid. By default it must have {@link #prefix} at the start. */
	protected boolean isValid (DatagramPacket packet) {
		if (!hasPrefix(packet)) {
			if (DEBUG) debug(category, "Server received invalid UDP packet, prefix: " + Util.toString(packet));
			return false;
		}
		return true;
	}

	static boolean hasPrefix (DatagramPacket packet) {
		int length = packet.getLength(), prefixLength = prefix.length;
		if (length < prefixLength) return false;
		byte[] data = packet.getData();
		for (int i = 0; i < prefixLength; i++)
			if (data[i] != prefix[i]) return false;
		return true;
	}

	static public void main (String[] args) throws Exception {
		TRACE();

		new BroadcastServer("server", "test", 53333).start();

		System.out.println(BroadcastClient.find("client", new UdpBroadcast(Subnets.classC), 53333, 1000).getAddress());
	}
}
