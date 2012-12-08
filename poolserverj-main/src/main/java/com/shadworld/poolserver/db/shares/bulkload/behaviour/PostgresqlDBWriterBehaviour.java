package com.shadworld.poolserver.db.shares.bulkload.behaviour;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import com.shadworld.poolserver.db.shares.bulkload.AbstractBulkLoader;

public class PostgresqlDBWriterBehaviour extends DBWriterBehaviour {

	public PostgresqlDBWriterBehaviour(AbstractBulkLoader bulkLoader) {
		super(bulkLoader);
		// TODO Auto-generated constructor stub
	}

	@Override
	public void startDbWrite(Connection conn, Statement stmt, InputStream in) throws SQLException, IOException {
		if (bulkLoader.isLocalMode()) {
			final CopyManager copyManager = new CopyManager(conn.unwrap(BaseConnection.class));
			copyManager.copyIn(bulkLoader.getBulkLoadQuery(), in);
		} else {
			stmt.execute(bulkLoader.getBulkLoadQuery());
		}
	}

}
