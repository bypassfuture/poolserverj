package com.shadworld.poolserver.stats;

import com.shadworld.poolserver.PSJExchange;
import com.shadworld.util.MovingAverage;

public class WorkSubmitStats {
	
	
	MovingAverage httpRequestFailRate = new MovingAverage(Stats.MA_PERIOD_MED);
	
	MovingAverage httpTripTimeSuccess = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage httpTripTimeHeaderComplete = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage httpTripTimeFail = new MovingAverage(Stats.MA_PERIOD_MED);
	MovingAverage httpTripTimeExpire = new MovingAverage(Stats.MA_PERIOD_MED);
	
	int httpRequestsIssued = 0;

	
	/**
	 * Level 1 stat - use for internal optimization
	 */
	public void registerHttpReponseComplete(PSJExchange ex, long triptime) {
		httpRequestFailRate.addValue(0);
		httpTripTimeSuccess.addValue(triptime);
	}
	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpReponseHeaderComplete(PSJExchange ex, long triptime) {
		httpTripTimeHeaderComplete.addValue(triptime);
	}
	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpRetry(PSJExchange ex, long triptime) {
		// TODO Auto-generated method stub
		
	}

	/**
	 * Level 1 stat - use for internal optimization 
	 */
	public void registerHttpConnectionFail(PSJExchange ex, Throwable x, long triptime) {
		httpRequestFailRate.addValue(1);
		httpTripTimeFail.addValue(triptime);
	}

	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpException(PSJExchange ex, Throwable x, long triptime) {
		httpRequestFailRate.addValue(1);
		httpTripTimeFail.addValue(triptime);
	}

	
	/**
	 * Level 2 stat - use for performance tuning/troubleshooting
	 */
	public void registerHttpExpire(PSJExchange ex, long triptime) {
		httpRequestFailRate.addValue(1);
		httpTripTimeExpire.addValue(triptime);
	}


}
