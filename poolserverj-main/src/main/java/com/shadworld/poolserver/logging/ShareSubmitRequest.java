package com.shadworld.poolserver.logging;

public class ShareSubmitRequest {

	private ShareEntry entry;

	public ShareSubmitRequest(ShareEntry entry) {
		super();
		this.entry = entry;
	}
	
	/**
	 * @return the entry
	 */
	public ShareEntry getShareEntry() {
		return entry;
	}

	
}
