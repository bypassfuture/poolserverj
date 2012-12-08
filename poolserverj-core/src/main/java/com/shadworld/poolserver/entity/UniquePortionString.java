package com.shadworld.poolserver.entity;

import java.io.Serializable;


/**
 * an unmodifiable string holder that precomputes hashcode using only the most
 * unique portion for fast map usage. equals is also implemented to check the
 * mostly likely unique parts of the string first.
 * 
 * @author git
 * 
 */
public class UniquePortionString implements Serializable {

	final String string;
	final int hash;
	
	public UniquePortionString(String string) {
		super();
		this.string = string;
		
		// compute hashcode
		int h = 0;

		//add last 3 chars of timestamp field (unique every 4096 seconds)
//		h = h = 31 * string.charAt(71);
//		h = 31 * h + string.charAt(70);
//		h = 31 * h + string.charAt(69);
		
		//add last 5 chars of merkleroot which is unique every 1048576 combinations
		h = 31 * h + string.charAt(64);
		h = 31 * h + string.charAt(63);
		h = 31 * h + string.charAt(62);
		h = 31 * h + string.charAt(61);
		h = 31 * h + string.charAt(60);
		
		hash = h;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return hash;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
		    return true;
		}
		if (obj instanceof UniquePortionString) {
			UniquePortionString anotherString = (UniquePortionString)obj;
		    if (anotherString.hash != hash)
		    	return false;
		    return string.equals(anotherString.string);
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		// TODO Auto-generated method stub
		return string;
	}

}
