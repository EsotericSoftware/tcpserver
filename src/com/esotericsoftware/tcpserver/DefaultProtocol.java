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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

import com.esotericsoftware.tcpserver.Protocol.ProtocolRead;
import com.esotericsoftware.tcpserver.Protocol.ProtocolWrite;

/** The default protocol for sending a string and an optional byte array. */
public class DefaultProtocol implements ProtocolRead, ProtocolWrite {
	static private final byte[] empty = new byte[0];

	private final Object outputLock = new Object();
	private final ArrayBlockingQueue sends = new ArrayBlockingQueue(1024, true);
	byte[] data = empty;

	public void readThread (Connection connection) throws IOException {
		DataInputStream input = connection.input;

		while (!connection.closed) {
			String message = input.readUTF();
			if (message == null || connection.closed) break;
			String event, payload;
			int index = message.indexOf(" ");
			if (index != -1) {
				event = message.substring(0, index).trim();
				payload = message.substring(index + 1).trim();
			} else {
				event = message.trim();
				payload = "";
			}

			int dataLength = readVarint(input);
			if (dataLength > 0) {
				if (data.length < dataLength) data = new byte[dataLength];
				if (!readFully(connection, data, 0, dataLength)) break;
			}

			if (TRACE) trace(connection.category, "Received: " + event + ", " + payload + (dataLength > 0 ? ", " + dataLength : ""));
			try {
				connection.receive(event, payload, data, dataLength);
			} catch (Throwable ex) {
				if (ERROR) error(connection.category, "Error processing message: " + message, ex);
				break;
			}
		}
	}

	public void writeThread (Connection connection) {
		while (!connection.closed) {
			try {
				Object object = sends.take();
				if (object instanceof String)
					sendBlocking(connection, (String)object, null, 0, 0);
				else {
					DefaultProtocol.Send send = (DefaultProtocol.Send)object;
					sendBlocking(connection, send.message, send.bytes, 0, send.count);
				}
			} catch (InterruptedException ignored) {
			}
		}
	}

	public void send (Connection connection, String message) {
		if (message == null) throw new IllegalArgumentException("message cannot be null.");

		if (TRACE) trace(connection.category, "Queued: " + message);
		sends.add(message);
	}

	public void send (Connection connection, String message, byte[] bytes, int offset, int count) {
		if (message == null) throw new IllegalArgumentException("message cannot be null.");
		if (bytes == null) {
			if (count != 0) throw new IllegalArgumentException("bytes cannot be null when count != 0: " + count);
		} else {
			if (count < 0) throw new IllegalArgumentException("count cannot be < 0");
		}

		if (TRACE) trace(connection.category, "Queued: " + message + ", " + count);
		byte[] copy = new byte[count];
		System.arraycopy(bytes, offset, copy, 0, count);
		DefaultProtocol.Send send = new Send();
		send.message = message;
		send.bytes = copy;
		send.count = count;
		sends.add(send);
	}

	public boolean sendBlocking (Connection connection, String message, byte[] bytes, int offset, int count) {
		if (message == null) throw new IllegalArgumentException("message cannot be null.");
		if (bytes == null) {
			if (count != 0) throw new IllegalArgumentException("bytes cannot be null when count != 0: " + count);
		} else {
			if (count < 0) throw new IllegalArgumentException("count cannot be < 0");
		}

		if (connection.closed) return false;
		try {
			synchronized (outputLock) {
				if (TRACE) trace(connection.category, "Sent: " + message + (count > 0 ? ", " + count : ""));
				DataOutputStream output = connection.output;
				output.writeUTF(message);
				writeVarint(count, output);
				if (count != 0) output.write(bytes, offset, count);
				output.flush();
			}
			return true;
		} catch (IOException ex) {
			if (ERROR && !connection.closed) error(connection.category, "Error writing to connection: " + message, ex);
			connection.close();
			return false;
		}
	}

	static class Send {
		String message;
		byte[] bytes;
		int count;
	}
}
