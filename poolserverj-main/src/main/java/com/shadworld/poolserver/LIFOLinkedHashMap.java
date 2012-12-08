package com.shadworld.poolserver;

import java.util.LinkedHashMap;

public class LIFOLinkedHashMap<K, V> extends LinkedHashMap<K, V> {

	private final int maxCapacity;

	public LIFOLinkedHashMap(int maxCapacity) {
		super(maxCapacity);
		this.maxCapacity = maxCapacity;
	}

	/* (non-Javadoc)
	 * @see java.util.LinkedHashMap#removeEldestEntry(java.util.Map.Entry)
	 */
	@Override
	protected boolean removeEldestEntry(java.util.Map.Entry<K, V> eldest) {
		return size() > maxCapacity;
	}
	
	
	
}
