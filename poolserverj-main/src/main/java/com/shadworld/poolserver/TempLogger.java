package com.shadworld.poolserver;

import java.io.File;

import com.shadworld.poolserver.conf.Res;
import com.shadworld.utils.FileUtil;

public class TempLogger {

	static File file = new File("real-share-submits.log");
	
	public static void logRealShare(String message) {
		if (Res.isDebug()) {
			if (!message.endsWith("\n"))
				message += "\n";
			FileUtil.appendStringToFile(message, file);
		}
	}
	
}
