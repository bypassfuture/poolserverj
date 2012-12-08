package com.shadworld.poolserver.servlet.auth;

import com.shadworld.poolserver.entity.Worker;

public class WorkerLoginEntry {
	public Worker worker;
	public String suppliedPassword;
	public String ip;
	public LoginResult loginResult;
	
	public enum LoginResult {
		OK, 
		BAD_PASSWORD, 
		DISALLOWED_HOST,
		/**
		 * worker not found in database and has previously attempted login
		 */
		WORKER_NOT_FOUND,
	}
}