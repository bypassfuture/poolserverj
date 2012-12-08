package com.shadworld.poolserver.db.shares;

import java.util.Collection;

import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.logging.ShareEntry;
import com.shadworld.poolserver.logging.ShareLogger;

public class BlackHoleSharesDBFlushEngine extends DefaultPreparedStatementSharesDBFlushEngine {

	public BlackHoleSharesDBFlushEngine(ShareLogger logger, String notUsed, String[] extraParams) {
		super(logger, notUsed, extraParams);
		// com.shadworld.poolserver.db.shares.BlackHoleSharesDBFlushEngine
	}

	/* (non-Javadoc)
	 * @see com.shadworld.poolserver.db.shares.DefaultPreparedStatementSharesDBFlushEngine#flushToDatabase(java.util.Collection)
	 */
	@Override
	public void flushToDatabase(Collection<ShareEntry> results) {
		//Res.logInfo("Not logging anything to database... Happy now?");
		return;
	}

	/* (non-Javadoc)
	 * @see com.shadworld.poolserver.db.shares.DefaultPreparedStatementSharesDBFlushEngine#updateCounters(java.util.Collection)
	 */
	@Override
	public void updateCounters(Collection<ShareEntry> results) {
		return;
	}
	
	

}
