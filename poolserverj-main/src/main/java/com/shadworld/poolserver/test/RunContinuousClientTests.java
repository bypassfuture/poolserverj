package com.shadworld.poolserver.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.eclipse.jetty.client.ContentExchange;
import org.json.JSONArray;
import org.json.JSONException;

import com.google.bitcoin.core.PSJBlock;
import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.jsonrpc.JsonUtil;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.servicelauncher.PoolServerService;
import com.shadworld.util.MovingAverage;
import com.shadworld.util.RateCalculator;
import com.shadworld.util.Time;
import com.shadworld.utils.Convert;

public class RunContinuousClientTests {

	/**
	 * @param args
	 * @throws InterruptedException
	 */
	public static void main(String[] args) throws InterruptedException {

		if (args.length == 0 || "--help".equalsIgnoreCase(args[0])) {
			PoolServerService.printUsage();
			System.exit(0);
		}

		Properties props = new Properties();

		try {
			props.load(new FileInputStream(new File(args[0])));
		} catch (Exception e1) {
			System.out.println(e1.getLocalizedMessage());
			System.exit(1);
		}

		ConfigureTest.configureTest();
		Res.setDebug(false);
		Random rand = new Random();

		boolean shortReport = Convert.toBoolean(props.getProperty("shortReport", "true"));

		int numClients = Convert.toInteger(props.getProperty("numConcurrentClients", "50"));
		final int avgDelay = Convert.toInteger(props.getProperty("getworkDelay", "0"));
		final int getWorkNum = 0;

		final boolean doWork = Convert.toBoolean(props.getProperty("doWork", "false"));
		final long workSubmitInterval = Convert.toLong(props.getProperty("workSubmitInterval", "100"));

		boolean spikeyRequests = Convert.toBoolean(props.getProperty("spikeyRequests", "false"));
		long reportInterval = 2000;
		long spikeTime = Convert.toLong(props.getProperty("spikeTime", "100"));
		long sleepTime = Convert.toLong(props.getProperty("sleepTime", "200"));
		final String serverUrl = props.getProperty("serverUrl", "http://localhost:8999/");
		String usernamePrefix = props.getProperty("usernamePrefix", "test-worker-getworkClient");
		final String password = props.getProperty("password", "test");

		final boolean[] rest = new boolean[] { false };

		final RateCalculator rate = new RateCalculator(numClients * 100);
		final RateCalculator rateMed = new RateCalculator(numClients * 1000);
		final RateCalculator rateLong = new RateCalculator(numClients * 10000);

		final RateCalculator[] workRates = new RateCalculator[3];

		if (doWork) {
			workRates[0] = new RateCalculator(numClients * 100);
			workRates[1] = new RateCalculator(numClients * 1000);
			workRates[2] = new RateCalculator(numClients * 10000);
		}

		final MovingAverage<Long> tripTime = new MovingAverage(numClients * 1000);
		final MovingAverage<Long> submitTime = new MovingAverage(numClients * 1000);

		// verify local daemon is responding
		// JsonRpcClient lclient = new JsonRpcClient("http://10.1.1.10:8332/",
		// "rpcuser", "rpcpassword");
		// JsonRpcRequest lrequest = new JsonRpcRequest("getblocknumber",
		// lclient.newRequestId());
		// try {
		// L.println(lclient.doRequest(lrequest).toJSONObject().toString(4));
		// } catch (JSONException e) {
		// e.printStackTrace();
		// }
		Res.setDebug(false);

		Thread t = null;
		final int[] successes = new int[numClients];
		final int[] response503 = new int[numClients];
		final int[] fails = new int[numClients];
		final int[] workSubmits = new int[numClients];
		final long[] lastWork = new long[1];

		for (int i = 0; i < numClients; i++) {
			final int index = i;
			final String name = usernamePrefix + ((i % 10) + 1);
			Thread runClientThread = new Thread(usernamePrefix + (i)) {
				public void run() {
					Random rand = new Random();
					int maxWait = rand.nextInt(avgDelay) + (avgDelay / 4);
					JsonRpcClient client = new JsonRpcClient(serverUrl, name, password);
					long num = 0;
					while (true) {
						num++;
						if (rest[0]) {
							try {
								Thread.sleep(3);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							continue;
						}
						JsonRpcRequest request = new JsonRpcRequest("getwork", client.newRequestId());
						JsonRpcResponse response;
						try {
							long start = System.currentTimeMillis();
							response = client.doRequest(request);
							synchronized (tripTime) {
								tripTime.addValue(System.currentTimeMillis() - start);
							}
							if (response.getResult() != null) {
								try {
									response.getResult().getString("data").length();
									successes[index] = successes[index] + 1;
								} catch (Exception e) {

								}
								rate.registerEvent();
								rateMed.registerEvent();
								rateLong.registerEvent();
								if (doWork) {
									if (System.currentTimeMillis() > lastWork[0] + workSubmitInterval) {
										lastWork[0] = System.currentTimeMillis();
										PSJBlock block = new PSJBlock(Res.networkParameters(), response.getResult()
												.getString("data"));
										block.solveForTarget(Res.getEasyDifficultyTarget());
										request = new JsonRpcRequest("getwork", client.newRequestId(),
												block.toSolutionString());
										long startSubmit = System.currentTimeMillis();
										client.doRequest(request);
										workSubmits[index] = workSubmits[index] + 1;
										synchronized (submitTime) {
											submitTime.addValue(System.currentTimeMillis() - startSubmit);
										}
										workRates[0].registerEvent();
										workRates[1].registerEvent();
										workRates[2].registerEvent();
									}
								}
							}
						} catch (Exception e) {
							fails[index] = fails[index] + 1;
							ContentExchange ex = client.getLastExchange();
							if (ex != null) {
								if (ex.getResponseStatus() == 503) {
									response503[index] = response503[index] + 1;
								} else {
									System.out.println("Status: " + ex.getResponseStatus());
								}
							} else {

							}
						}
						try {
							Thread.sleep(rand.nextInt(maxWait + 1));
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			};
			t = runClientThread;
			runClientThread.start();

		}
		NumberFormat nf = NumberFormat.getInstance();
		nf.setMaximumFractionDigits(2);
		long start = System.currentTimeMillis();

		long lastReport = System.currentTimeMillis();
		long lastStateChange = System.currentTimeMillis();

		while (true) {
			long now = System.currentTimeMillis();
			if (lastReport < now - reportInterval) {
				lastReport = now;
				System.out.println("----------------------");

				try {
					System.out.println("Requests - Rate Short: " + nf.format(rate.currentRate(Time.SEC)) + "/s"
							+ " Rate Med: " + nf.format(rateMed.currentRate(Time.SEC)) + "/s" + " Rate Long: "
							+ nf.format(rateLong.currentRate(Time.SEC)) + "/s");
				} catch (Exception e) {
				}
				if (doWork) {
					try {
						System.out.println("Work -   Rate Short: " + nf.format(workRates[0].currentRate(Time.SEC))
								+ "/s" + " Rate Med: " + nf.format(workRates[1].currentRate(Time.SEC)) + "/s"
								+ " Rate Long: " + nf.format(workRates[2].currentRate(Time.SEC)) + "/s");
					} catch (Exception e) {
					}
				}
				System.out.print("Receive Http avg trip time: " + nf.format(tripTime.getAvg()) + " ms");
				if (doWork) {
					System.out.println(" *** Submit Http avg trip time: " + nf.format(submitTime.getAvg()) + " ms");
				} else {
					System.out.println();
				}
				if (shortReport) {
					System.out.print("Success: " + arraySum(successes) + " Fail: " + arraySum(fails)
							+ " response503: " + arraySum(response503));
					if (doWork)
						System.out.println(" Work Submits: " + arraySum(workSubmits));
					else
						System.out.println();
				} else {
					System.out.println("Success:         " + Arrays.toString(successes));
					System.out.println("Response503:     " + Arrays.toString(response503));
					System.out.println("Fails:           " + Arrays.toString(fails));
					if (doWork) 
					System.out.println("Work Submits:    " + Arrays.toString(workSubmits));
				}
			}
			if (spikeyRequests) {
				if (rest[0]) {
					if (lastStateChange < now - sleepTime) {
						lastStateChange = now;
						rest[0] = false;
					}
				} else {
					if (lastStateChange < now - spikeTime) {
						lastStateChange = now;
						rest[0] = true;
					}
				}
			}
			Thread.sleep(5);
		}

	}

	public static int arraySum(int[] array) {
		int sum = 0;
		for (int i = 0; i < array.length; i++)
			sum += array[i];
		return sum;
	}
}
