package com.shadworld.poolserver.db.shares.bulkload;

import com.shadworld.poolserver.logging.ShareLogger;

/**
 * Fallback loader for windows users who cannot use DirectFifoBulkLoader
 * 
 * This engine differs in that it writes out the complete dataset to a temp file
 * before calling the database to load the file.  If you have a decent file system
 * cache it may be written, read and deleted before it ever needs to be committed to
 * disk.
 * 
 * In some cases this may be more efficient than using a localmode loader.
 * 
 * @author git
 *
 */
public class DirectSerialWriteReadBulkLoader extends AbstractDirectBulkLoader {

	public DirectSerialWriteReadBulkLoader(ShareLogger logger, String bulkLoadFilename, String[] extraParams) {
		super(logger, bulkLoadFilename,extraParams);
		setFifoMode(false);
	}

	@Override
	protected boolean isReplaceTmpFile() {
		return true;
	}

}
