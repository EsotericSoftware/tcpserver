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

/** A thread which can be started and stopped which calls {@link #retry()} repeatedly, sleeping when a try has failed. */
public abstract class Retry {
	protected final String category, name;
	protected volatile boolean running;
	final Object runLock = new Object();
	volatile Thread runThread;
	int delayIndex;
	int[] retryDelays = new int[] {1 * 1000, 3 * 1000, 5 * 1000, 8 * 1000, 13 * 1000};

	public Retry (String category, String name) {
		this.category = category;
		this.name = name;
	}

	public void start () {
		synchronized (runLock) {
			stop();
			if (TRACE) trace(category, "Started " + name + " thread.");
			delayIndex = 0;
			running = true;
			runThread = new Thread(name) {
				public void run () {
					try {
						while (running)
							retry();
						Retry.this.stop();
					} finally {
						if (TRACE) trace(category, "Stopped " + name + " thread.");
						synchronized (runLock) {
							runThread = null;
							runLock.notifyAll();
						}
					}
				}
			};
			runThread.start();
		}
	}

	abstract protected void retry ();

	protected void success () {
		delayIndex = 0;
	}

	protected void failed () {
		try {
			Thread.sleep(retryDelays[delayIndex]);
		} catch (InterruptedException ignored) {
		}
		delayIndex++;
		if (delayIndex == retryDelays.length) delayIndex = 0;
	}

	public void stop () {
		synchronized (runLock) {
			if (!running) return;
			running = false;
			Thread runThread = this.runThread;
			if (runThread == Thread.currentThread()) return;
			if (TRACE) trace(category, "Waiting for " + name + " thread to stop...");
			runThread.interrupt();
			stopping();
			while (this.runThread == runThread) {
				try {
					runLock.wait();
				} catch (InterruptedException ex) {
				}
			}
		}
	}

	protected void stopping () {
	}

	public void setRetryDelays (int... retryDelays) {
		this.retryDelays = retryDelays;
	}
}
