package com.shadworld.poolserver.servlet.auth;

import com.shadworld.poolserver.WorkerProxy;
import com.shadworld.utils.StringTools;

public class AnyPasswordWorkerAuthenticator extends WorkerAuthenticator {

	boolean allowBlankPasswords = true;
	boolean enforceBlankPasswords = false;
	
	public AnyPasswordWorkerAuthenticator(WorkerProxy workerProxy, String[] extraParams) {
		super(workerProxy, extraParams);
		
		//do custom config here including parsing any extraParams
		if ("false".equalsIgnoreCase(extraParams[0]))
			allowBlankPasswords = false;
		if (extraParams.length > 1 && "true".equalsIgnoreCase(extraParams[1]))
			enforceBlankPasswords = true;
		if (enforceBlankPasswords && !allowBlankPasswords)
			throw new IllegalArgumentException("Cannot allowBlankPasswords if enforcingBlankPasswords");
	}

	/* (non-Javadoc)
	 * @see com.shadworld.poolserver.servlet.auth.WorkerAuthenticator#passwordMatch(java.lang.String, java.lang.String)
	 */
	@Override
	protected boolean passwordMatch(String password, String suppliedPassword) {
		if (!allowBlankPasswords && StringTools.nullOrEmpty(suppliedPassword))
			return false;
		else if (enforceBlankPasswords && !StringTools.nullOrEmpty(suppliedPassword))
			return false;
		else
			return true;
	}
}
