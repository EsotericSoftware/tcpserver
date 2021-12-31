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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class BroadcastServer extends Retry {
	static public final byte[] prefix = new byte[] {62, 126, -17, 61, 127, -16, 63, 125, -18};

	private int port;
	private DatagramSocket socket;
	private final byte[] buffer = new byte[prefix.length];

	public BroadcastServer (String category, String name) {
		this(category, name, 0);
	}

	public BroadcastServer (String category, String name, int port) {
		super(category, name);
		this.port = port;
	}

	protected void retry () {
		try {
			socket = new DatagramSocket(port);
		} catch (Exception ex) {
			if (ERROR) error(category, "Unable to start broadcast server.", ex);
			failed();
			return;
		}
		success();
		try {
			if (INFO) info(category, "Listening on port: UDP " + port);
			byte[] receiveBuffer = receiveBuffer();
			outer:
			while (running) {
				DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
				try {
					socket.receive(packet);
				} catch (SocketException ex) {
					if (!running) return;
					throw ex;
				}

				int n = prefix.length;
				if (packet.getLength() < n) {
					if (DEBUG) debug(category, "Server received invalid packet, length: " + packet.getLength());
				} else {
					for (int i = 0; i < n; i++) {
						if (receiveBuffer[i] != prefix[i]) {
							if (DEBUG) {
								StringBuilder buffer = new StringBuilder();
								for (i = 0; i < n; i++)
									buffer.append(Integer.toHexString(receiveBuffer[i]) + " ");
								buffer.setLength(buffer.length() - 1);
								debug(category, "Server received invalid packet, prefix: " + buffer);
							}
							continue outer;
						}
					}

					byte[] responseBuffer = responseBuffer(packet);
					System.arraycopy(prefix, 0, responseBuffer, 0, prefix.length);
					packet = new DatagramPacket(responseBuffer, responseBuffer.length, packet.getAddress(), packet.getPort());
					socket.send(packet);
				}
			}
		} catch (Exception ex) {
			if (ERROR) error(category, "Unexpected broadcast server error.", ex);
			closeQuietly(socket);
			failed();
		} finally {
			if (INFO) info(category, "Server stopped: UDP " + port);
		}
	}

	/** Returns the buffer into which the received packet will be written. It must be at least large enough for {@link #prefix}. */
	protected byte[] receiveBuffer () {
		return buffer;
	}

	/** Returns the buffer which is sent as a response. It must be at least large enough for {@link #prefix}, which is written at
	 * the start. */
	protected byte[] responseBuffer (DatagramPacket packet) {
		return buffer;
	}

	protected void stopped () {
		closeQuietly(socket);
	}

	public int getPort () {
		return port;
	}

	public void setPort (int port) {
		this.port = port;
	}

	static public void main (String[] args) throws Exception {
		TRACE();

		BroadcastServer server = new BroadcastServer("broadcast", "test", 53333);
		server.start();

		System.out.println(BroadcastClient.find(53333, 1000).getAddress());
	}
}
