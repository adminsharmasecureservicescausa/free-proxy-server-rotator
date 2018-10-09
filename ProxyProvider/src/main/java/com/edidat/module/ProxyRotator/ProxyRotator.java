package com.edidat.module.ProxyRotator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.edidat.module.ProxyRotator.pojo.NetworkProxy;
import com.edidat.module.ProxyRotator.providers.ProxyProvider;
import com.edidat.module.ProxyRotator.providers.ProxyProvidersInJsonFormat;

/**
 * @author rohit.ihare Proxy rotator is the singleton class, which maintains a
 *         static blocking queue which is filled by multiple proxy extractor
 *         threads. It also provides method to get a proxy from queue. When
 *         server shutdowns, queue get drained to a file for backingup the
 *         proxies for future use.
 */
public class ProxyRotator {

	private static final Logger logger = LogManager.getLogger(ProxyRotator.class);

	// private BlockingQueue<NetworkProxy> proxyQueue = new
	// LinkedBlockingQueue<>(20);

	private Map<Protocol, BlockingQueue<NetworkProxy>> queueMap = new HashMap<>();

	private volatile static ProxyRotator proxyRotatorSingletonInstance;

	private static final Object lock = new Object();;

	private ProxyProvider providers[] = new ProxyProvider[] {
			new ProxyProvidersInJsonFormat("PubP", "http://pubproxy.com/api/proxy?format=json&https=true", 100, 1440,
					"data[0].ip", "data[0].port", Protocol.HTTPS),
			new ProxyProvidersInJsonFormat("Gimme",
					"https://gimmeproxy.com/api/getProxy?supportsHttps=true", 10, 1440, "ip", "port",
					Protocol.HTTPS),
			new ProxyProvidersInJsonFormat("getproxy", "https://api.getproxylist.com/proxy?allowHttps=1", 30, 1440,
					"ip", "port", Protocol.HTTPS),
			new ProxyProvidersInJsonFormat("PubP", "http://pubproxy.com/api/proxy?format=json&https=false", 100, 1440,
					"data[0].ip", "data[0].port", Protocol.HTTP),
			new ProxyProvidersInJsonFormat("Gimme",
					"https://gimmeproxy.com/api/getProxy?supportsHttps=false", 10, 1440, "ip", "port",
					Protocol.HTTP),
			new ProxyProvidersInJsonFormat("getproxy", "https://api.getproxylist.com/proxy", 30, 1440, "ip", "port",
					Protocol.HTTP)};

	ExecutorService executorService = Executors.newFixedThreadPool(10);

	private ProxyRotator() {

	}

	public static ProxyRotator getInstance() {
		ProxyRotator r = proxyRotatorSingletonInstance;
		if (r == null) {
			synchronized (lock) {
				r = proxyRotatorSingletonInstance;
				if (r == null) {
					r = new ProxyRotator();
					proxyRotatorSingletonInstance = r;
				}
			}
		}
		return r;
	}

	/**
	 * Loads the queue with pre-backup proxies and sets a shutdown hook to drain the
	 * queue into backup file.
	 * 
	 * @throws ProxyRotatorException
	 */
	public void init(Set<Protocol> protocols) throws ProxyRotatorException {
		String dataDirectory = System.getenv(Constants.DATA_DIR);
		String proxyPersistenceFile = dataDirectory + File.separator + Constants.PROXY_PERSISTENCE_FILE;

		if (dataDirectory == null || proxyPersistenceFile == null) {
			throw new ProxyRotatorException("Data directory / Proxy file path Environment variables are not set.");
		}

		// Load pre backup proxies into queue
		loadQueueFromFile(proxyPersistenceFile);

		loadQueueFromProviders(protocols);

		// Drains queue to a file when engine shutdowns.
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				logger.info("Draining queue to file.");
				FileWriter writer = null;
				try {
					writer = new FileWriter(proxyPersistenceFile);
					ArrayList<NetworkProxy> proxies = new ArrayList<NetworkProxy>();
					Set<Entry<Protocol, BlockingQueue<NetworkProxy>>> entrySet = queueMap.entrySet();
					for (Entry<Protocol, BlockingQueue<NetworkProxy>> entry : entrySet) {
						BlockingQueue<NetworkProxy> proxyQueue = entry.getValue();
						proxyQueue.drainTo(proxies);
						for (NetworkProxy networkProxy : proxies) {
							writer.write(networkProxy.getProtocol() + ":" + networkProxy.getIpAddress() + ":"
									+ networkProxy.getPort() + ":" + networkProxy.getOrigin() + "\n");
						}

					}
				} catch (IOException e) {
					logger.error(e.getMessage());
				} finally {
					try {
						writer.close();
					} catch (IOException e) {
						logger.error(e.getMessage());
					}
				}
			}
		});

	}

	private void loadQueueFromProviders(Set<Protocol> protocols) {
		for (ProxyProvider provider : providers) {
			if (protocols.contains(provider.getRequiredProtocol())) {
				executorService.submit(provider);
			}
		}
	}

	/**
	 * Put the proxy to queue, if the proxy is reachable.
	 * 
	 * @param networkProxy
	 */
	public void putToQueue(NetworkProxy networkProxy) {
		try {
			Document document = Jsoup.connect("http://www.msftncsi.com/ncsi.txt").timeout(60000)
					.proxy(networkProxy.getIpAddress(), networkProxy.getPort()).get();
			if (document == null) {
				throw new Exception("Null document returned.");
			}
			queueMap.putIfAbsent(networkProxy.getProtocol(), new LinkedBlockingQueue<>());
			queueMap.get(networkProxy.getProtocol()).put(networkProxy);
			logger.info("Queue Status :  {}",queueMap.get(networkProxy.getProtocol()).toString());
		} catch (Exception e) {
			logger.warn("Proxy {} is not rechable/ not working. : {}", networkProxy, e.getMessage());
		}
	}

	/**
	 * this method removes first element from queue and re-insert it into queue.
	 * 
	 * @return if queue is empty it return null or an object of NetworkProxy
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public NetworkProxy removeInsert(Protocol protocol) throws InterruptedException, IOException {
		NetworkProxy networkProxy = null;
		BlockingQueue<NetworkProxy> queue = queueMap.get(protocol);
		if (queue == null) {
			logger.warn("Rotated is not started for {}" + protocol.name + " mode.");
			return null;
		}
		networkProxy = queue.poll();
		if (networkProxy == null) {
			return null;
		}
		putToQueue(networkProxy);
		return networkProxy;
	}

	public NetworkProxy getNextProxy(Protocol protocol) {
		NetworkProxy networkProxy = null;
		try {
			networkProxy = removeInsert(protocol);
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
		return networkProxy;
	}

	private void loadQueueFromFile(String filePath) throws ProxyRotatorException {
		RandomAccessFile read = null;
		try {

			File file = new File(filePath);
			read = new RandomAccessFile(file, "rw");
			String line = "";
			while ((line = read.readLine()) != null) {
				logger.info("Read proxy from file {}", line);
				String[] ipPortPair = line.split(":");
				InetAddress addr = InetAddress.getByName(ipPortPair[1]);
				if (addr.isReachable(2000)) {
					try {
						queueMap.putIfAbsent(Protocol.valueOf(ipPortPair[0]), new LinkedBlockingQueue<>());
						queueMap.get(Protocol.valueOf(ipPortPair[0]))
								.add(new NetworkProxy(Protocol.valueOf(ipPortPair[0]), ipPortPair[1],
										Integer.parseInt(ipPortPair[2]), ipPortPair[3]));
					} catch (IllegalStateException e) {
						logger.warn("Queue is already full");
						break;
					}
				}
			}
		} catch (Exception e) {
			logger.error(e.getMessage());
			throw new ProxyRotatorException(e.getMessage(), e);
		} finally {
			if (read != null) {
				try {
					read.close();
				} catch (IOException e) {
					logger.error(e.getMessage());
					throw new ProxyRotatorException(e.getMessage(), e);
				}
			}
		}
	}
}
