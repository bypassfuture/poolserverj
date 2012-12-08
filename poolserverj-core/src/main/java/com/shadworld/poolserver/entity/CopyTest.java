package com.shadworld.poolserver.entity;

import org.apache.commons.lang.RandomStringUtils;

import com.shadworld.utils.L;

/**
 * Benchmarks System.arraycopy() vs. a for loop Expects as command line
 * arguments paris of array size and number of repetitions of the benchmark.
 * 
 * @author Michael Borgwardt
 */
public class CopyTest {
	static char[] in;
	static char[] out;

	public static void main(String[] args) {
		
		long now = System.currentTimeMillis();
		int tail = (int) now;
		long base = now - tail;
		long add = base + tail;
		
		L.println("now:  " + now);
		L.println("tail: " + tail);
		L.println("base: " + base);
		L.println("add:  " + add);
		L.println("now:  " + now);
		
		
		
		int[] arg = new int[]{1000, 1000000, 5, 10000000, 20, 10000000, 100, 10000000};
		
		try {
			for (int n = 0; n < arg.length / 2; n++) {
				int size = arg[n * 2];
				int iterations = arg[n * 2 + 1];

				in = RandomStringUtils.random(size).toCharArray();
				out = new char[size];
				
				

				long arraycopyTime = 0;
				long loopcopyTime = 0;

				long before = System.currentTimeMillis();
				for (int i = 0; i < iterations; i++) {
					System.arraycopy(in, 0, out, 0, size);
				}
				arraycopyTime = System.currentTimeMillis() - before;
				before = System.currentTimeMillis();
				for (int i = 0; i < iterations; i++) {
					for (int j = 0; j < in.length; j++) {
						out[j] = in[j];
					}
				}
				loopcopyTime = System.currentTimeMillis() - before;
				System.out.println("Array size: " + size + ", Repetitions: " + iterations);
				System.out.println("Loop: " + loopcopyTime + "ms, System.arrayCopy: " + arraycopyTime + "ms");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}