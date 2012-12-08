package com.shadworld.poolserver.db.shares.bulkload;

import com.shadworld.poolserver.logging.ShareLogger;

/**
 * This loader has the following dependency:
 * 
 * <br> - Must be in a *nix variant OS capable of making a fifo
 * 
 * You can create a fifo with the command:<br>
 * <code>mkfifo filename</code><br>
 * but this is not persistent so must be recreated everytime the server is started.  It would
 * be advised to wrap the poolserver daemon in a start script that ensures the fifo is created
 * before starting the server.  Note that fifo access blocks until the end you 
 * are trying to connect to is unused and then until the other end is connected.
 * This can sometimes result in problems if one process hangs and doesn't unhook itself
 * from the fifo.  It may become unusable and need to be removed (rm filename) and recreated.
 * 
 * @author git
 *
 */
public class LocalFifoBulkLoader extends AbstractLocalFileBulkLoader {

	public LocalFifoBulkLoader(ShareLogger logger, String bulkLoadFilename, String[] extraParams) {
		super(logger, bulkLoadFilename, extraParams);
		setFifoMode(true);
	}

	@Override
	protected boolean isReplaceTmpFile() {
		return false;
	}

}
