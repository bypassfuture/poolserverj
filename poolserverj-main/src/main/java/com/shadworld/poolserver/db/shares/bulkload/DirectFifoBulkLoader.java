package com.shadworld.poolserver.db.shares.bulkload;

import com.shadworld.poolserver.logging.ShareLogger;

/**
 * This is the fastest of all the bulkloaders but has the following dependencies:
 * <br> - Must run on the same server as the database.
 * <br> - Must be in a *nix variant OS capable of making a fifo
 * <br>  - Must create a fifo that is accessible by both the database server process
 * and the poolserver process.  On systems running apparmor or SELinux you will need
 * to allow access to the sql daemon process to the location of the fifo.
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
public class DirectFifoBulkLoader extends AbstractDirectBulkLoader {

	public DirectFifoBulkLoader(ShareLogger logger, String bulkLoadFilename, String[] extraParams) {
		super(logger, bulkLoadFilename, extraParams);
		setFifoMode(true);
	}

	@Override
	protected boolean isReplaceTmpFile() {
		return false;
	}

}
