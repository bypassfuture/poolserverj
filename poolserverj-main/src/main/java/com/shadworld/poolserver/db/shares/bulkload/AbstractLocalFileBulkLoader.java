package com.shadworld.poolserver.db.shares.bulkload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.sql.SQLException;

import com.shadworld.poolserver.logging.ShareLogger;
import com.shadworld.sql.Sql;

public abstract class AbstractLocalFileBulkLoader extends AbstractBulkLoader {


	
	public AbstractLocalFileBulkLoader(ShareLogger logger, String bulkLoadFilename, String[] extraParams) {
		super(logger, bulkLoadFilename, extraParams);
		// TODO Auto-generated constructor stub
	}

	@Override
	protected InputStream buildInputStream(String filename, Sql sql) throws SQLException, IOException {
		if (engine == ENGINE_POSTGRESSQL) {
			if (tmpFile == null)
				tmpFile = new File(filename);
			if (!fifoMode && isReplaceTmpFile() && tmpFile.exists())
				tmpFile.delete();
			else if (!fifoMode) {
				tmpFile.getParentFile().mkdirs();
			}
			if (!fifoMode && !tmpFile.exists())
				tmpFile.createNewFile();
			return new FileInputStream(tmpFile);
		}
		return null;
	}

	@Override
	protected Writer buildOutputWriter(String filename, InputStream in) throws IOException {
		if (tmpFile == null)
			tmpFile = new File(filename);
		if (!fifoMode && isReplaceTmpFile() && tmpFile.exists())
			tmpFile.delete();
		else if (!fifoMode) {
			tmpFile.getParentFile().mkdirs();
		}
		return new FileWriter(tmpFile);
	}

	@Override
	protected boolean isFifoMode() {
		return fifoMode;
	}

	@Override
	public boolean isLocalMode() {
		return true;
	}

	@Override
	protected abstract boolean isReplaceTmpFile();

	@Override
	public boolean isDisableKeys() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isDisableUniqueChecks() {
		return false;
	}

	@Override
	protected boolean useCompression() {
		return false;
	}

}
