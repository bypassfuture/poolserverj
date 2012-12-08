package com.shadworld.poolserver.db.shares.bulkload;

import com.shadworld.poolserver.logging.ShareLogger;

/**
 * This bulk loader writes out to temp file before sending to database via the
 * connection.  This method is probably slower than JavaPipesBulkLoader but it is
 * provided in case there are scenarios where it is faster.
 * @author git
 *
 */
public class LocalSerialWriteReadBulkLoader extends AbstractLocalFileBulkLoader {

	public LocalSerialWriteReadBulkLoader(ShareLogger logger, String bulkLoadFilename, String[] extraParams) {
		super(logger, bulkLoadFilename, extraParams);
		setFifoMode(false);
	}

	@Override
	protected boolean isReplaceTmpFile() {
		return true;
	}

}
