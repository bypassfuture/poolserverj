package com.shadworld.poolserver.entity;

import java.io.Serializable;

public class FastEqualsSolution implements Serializable {

	private final String solution;

	public FastEqualsSolution(String solution) {
		super();
		this.solution = solution;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return solution.hashCode();
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		FastEqualsSolution other = (FastEqualsSolution) obj;
		if (solution == null) {
			if (other.solution != null)
				return false;
		} else if (solution.length() != other.solution.length()){
			return false;
		} else {
			//check parts of the block header one by one in the order they are most likely to be unique.
			
			int i;
			//nonce reverse order
			for (i = 159; i >= 152; i--) {
				if (solution.charAt(i) != other.solution.charAt(i))
					return false;
			}

			//first 6 of merkle root - unique to 16 million
			for (i = 72; i < 80; i++) {
				if (solution.charAt(i) != other.solution.charAt(i))
					return false;
			}
			
			//timestamp - reverse order
			for (i = 143; i >= 136; i--) {
				if (solution.charAt(i) != other.solution.charAt(i))
					return false;
			}
			
			//remainder of merkleroot
			for (i = 80; i < 136; i++) {
				if (solution.charAt(i) != other.solution.charAt(i))
					return false;
			}
			
			//difficulty
			for (i = 144; i < 152; i++) {
				if (solution.charAt(i) != other.solution.charAt(i))
					return false;
			}
			
			//version + prev_block
			for (i = 0; i < 72; i++) {
				if (solution.charAt(i) != other.solution.charAt(i))
					return false;
			}
			
			//tx count is not contained in solution and is irrelevant to our purposes
			//sha padding should not be compared as it opens a security hole.
//			//tx count + sha256 padding
//			for (i = 160; i < solution.length(); i++) {
//				if (solution.charAt(i) != other.solution.charAt(i))
//					return false;
//			}
			
		}
		return true;
	}

	private boolean rangeEquals(final String other, int start, int end) {
		for (int i = start; i < end; i++) {
			if (solution.charAt(i) != other.charAt(i))
				return false;
		}
		return true;
	}
	
	private boolean rangeEqualsReverse(final String other, int start, int end) {
		for (int i = end; i >= start; i--) {
			if (solution.charAt(i) != other.charAt(i))
				return false;
		}
		return true;
	}
	
	
	
}
