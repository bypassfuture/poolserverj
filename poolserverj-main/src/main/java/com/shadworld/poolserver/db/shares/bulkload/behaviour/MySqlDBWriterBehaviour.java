package com.shadworld.poolserver.db.shares.bulkload.behaviour;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.shadworld.poolserver.db.shares.bulkload.AbstractBulkLoader;
import com.shadworld.poolserver.logging.ShareLogger;

public class MySqlDBWriterBehaviour extends DBWriterBehaviour {

	public MySqlDBWriterBehaviour(AbstractBulkLoader bulkLoader) {
		super(bulkLoader);
	}

	@Override
	public void startDbWrite(Connection conn, Statement stmt, InputStream in) throws SQLException {
		boolean disableUniqueChecks = bulkLoader.isDisableUniqueChecks();
		boolean disableKeys = bulkLoader.isDisableKeys();
		boolean doInTx = disableKeys || disableUniqueChecks;
		boolean batchRun = (in != null && doInTx && in instanceof PipedInputStream) || !bulkLoader.isLocalMode();
		if (in instanceof PipedInputStream)
			batchRun = false;
		
		if (doInTx)
			stmt.addBatch("START TRANSACTION");
		if (disableUniqueChecks)
			stmt.addBatch("SET UNIQUE_CHECKS=0");
		if (disableKeys)
			stmt.addBatch("ALTER TABLE shares DISABLE KEYS");
		if (doInTx && !batchRun)
			stmt.executeBatch();
		if (batchRun)
			stmt.addBatch(bulkLoader.getBulkLoadQuery());
		else
			stmt.execute(bulkLoader.getBulkLoadQuery());
		
		if (disableUniqueChecks)
			stmt.addBatch("ALTER TABLE shares ENABLE KEYS");
		if (disableUniqueChecks)
			stmt.addBatch("SET UNIQUE_CHECKS=1");
		if (doInTx)
			stmt.addBatch("COMMIT");
		if (doInTx || batchRun)
			stmt.executeBatch();

	}

}
