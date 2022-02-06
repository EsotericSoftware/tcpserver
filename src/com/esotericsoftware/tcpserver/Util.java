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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;

public class Util {
	static public void closeQuietly (Closeable closeable) {
		if (closeable == null) return;
		try {
			closeable.close();
		} catch (Throwable ignored) {
		}
	}

	/** Write an int using variable length encoding (1-5 bytes). */
	static public void writeVarint (int value, OutputStream output) throws IOException {
		if (value >>> 7 == 0) {
			output.write(value);
			return;
		}
		if (value >>> 14 == 0) {
			output.write((value & 0x7F) | 0x80);
			output.write(value >>> 7);
			return;
		}
		if (value >>> 21 == 0) {
			output.write((value & 0x7F) | 0x80);
			output.write(value >>> 7 | 0x80);
			output.write(value >>> 14);
			return;
		}
		if (value >>> 28 == 0) {
			output.write((value & 0x7F) | 0x80);
			output.write(value >>> 7 | 0x80);
			output.write(value >>> 14 | 0x80);
			output.write(value >>> 21);
			return;
		}
		output.write((value & 0x7F) | 0x80);
		output.write(value >>> 7 | 0x80);
		output.write(value >>> 14 | 0x80);
		output.write(value >>> 21 | 0x80);
		output.write(value >>> 28);
	}

	/** Read an int using variable length encoding (1-5 bytes). */
	static public int readVarint (InputStream input) throws IOException {
		int b = input.read();
		int result = b & 0x7F;
		if ((b & 0x80) == 0) return result;
		b = input.read();
		result |= (b & 0x7F) << 7;
		if ((b & 0x80) == 0) return result;
		b = input.read();
		result |= (b & 0x7F) << 14;
		if ((b & 0x80) == 0) return result;
		b = input.read();
		result |= (b & 0x7F) << 21;
		if ((b & 0x80) == 0) return result;
		b = input.read();
		return result | (b & 0x7F) << 28;
	}

	static public String toString (DatagramPacket packet) {
		int length = packet.getLength();
		byte[] data = packet.getData();
		StringBuilder buffer = new StringBuilder(length << 1);
		for (int i = 0; i < length; i++)
			buffer.append(Integer.toHexString(data[i] & 0xff) + ' ');
		buffer.setLength(buffer.length() - 1);
		return buffer.toString();
	}
}
