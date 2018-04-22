
package com.esotericsoftware.tcpserver;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
}
