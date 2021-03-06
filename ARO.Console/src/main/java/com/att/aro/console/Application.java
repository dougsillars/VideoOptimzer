/*
 *  Copyright 2017 AT&T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.att.aro.console;

import java.awt.event.ActionListener;
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.android.ddmlib.IDevice;
import com.att.aro.console.printstreamutils.ImHereThread;
import com.att.aro.console.printstreamutils.NullOut;
import com.att.aro.console.printstreamutils.OutSave;
import com.att.aro.console.util.UtilOut;
import com.att.aro.core.AROConfig;
import com.att.aro.core.IAROService;
import com.att.aro.core.SpringContextUtil;
import com.att.aro.core.bestpractice.pojo.BestPracticeType;
import com.att.aro.core.configuration.pojo.Profile;
import com.att.aro.core.datacollector.IDataCollector;
import com.att.aro.core.datacollector.IDataCollectorManager;
import com.att.aro.core.datacollector.pojo.CollectorStatus;
import com.att.aro.core.datacollector.pojo.StatusResult;
import com.att.aro.core.fileio.IFileManager;
import com.att.aro.core.mobiledevice.pojo.IAroDevice;
import com.att.aro.core.mobiledevice.pojo.IAroDevice.AroDeviceState;
import com.att.aro.core.mobiledevice.pojo.IAroDevice.Platform;
import com.att.aro.core.mobiledevice.pojo.IAroDevices;
import com.att.aro.core.packetanalysis.pojo.AbstractTraceResult;
import com.att.aro.core.packetanalysis.pojo.AnalysisFilter;
import com.att.aro.core.pojo.AROTraceData;
import com.att.aro.core.pojo.ErrorCode;
import com.att.aro.core.util.Util;
import com.att.aro.core.video.pojo.VideoOption;
import com.att.aro.mvc.AROController;
import com.att.aro.mvc.IAROView;
import com.beust.jcommander.JCommander;


public final class Application implements IAROView {
	// private final Logger LOGGER =
	// LoggerFactory.getLogger(Application.class);
	private final Logger LOGGER = Logger.getLogger(Application.class);

	private UtilOut utilOut;

	private AROController aroController;

	private IAroDevices aroDevices;

	private Commands cmds;

	private List<IDataCollector> collectorList;

	ResourceBundle buildBundle = ResourceBundle.getBundle("build");
	
	private Application(String[] args) {
		
		ApplicationContext context = SpringContextUtil.getInstance().getContext();

		aroController = new AROController(this);

		loadCommands(args);
		utilOut = cmds.isVerbose() ? new UtilOut() : new UtilOut(UtilOut.MessageThreshold.Normal);

		OutSave outSave = prepareSystemOut();
		try {
			LOGGER.debug("ARO Console app start");
		} finally {
			restoreSystemOut(outSave);
		}

		// command sanity check, if fails then reverts to help
		if (cmds.isHelp() 
				|| !((cmds.isListcollector() || cmds.isListDevices()) 
						|| !(cmds.getAnalyze() == null 
							&& cmds.getStartcollector() == null 
							&& cmds.getAsk() == null
							))) {
			usageHelp();
			System.exit(1);
		}

		collectorList = aroController.getAvailableCollectors();
		if (collectorList == null || collectorList.size()==0){
			outln("Error: There are no collectors installed!");
			restoreSystemOut(outSave);
			System.exit(1);
		}		

		if (cmds.isListcollector()) {
			showCollector(context, cmds);
			System.exit(1);
		}
		if (cmds.isListDevices()) {
			showDevices(context, cmds);
			System.exit(1);
		}
		// validate command entered
		ErrorCode error = new Validator().validate(cmds, context);
		if (error != null) {
			printError(error);
			System.exit(1);
		}
		
		if ((cmds.getStartcollector() != null || cmds.getAsk() != null) && cmds.getOutput() == null) {
			outln("Error: No output tracefolder was entered\n");
			System.exit(1);
		}
		
		// ask user for device selection
		if (cmds.getAsk() != null) {
			selectDevice(context, outSave);
		}

		// start the collector
		if (cmds.getStartcollector() != null) {
			runDataCollector(context, cmds);
		} else if (cmds.getAnalyze() != null) {
			runAnalyzer(context, cmds);
		}

		outSave = prepareSystemOut();
		try {
			LOGGER.debug("Console app ended");
		} finally {
			restoreSystemOut(outSave);
		}

	}

	private void selectDevice(ApplicationContext context, OutSave outSave) {
		aroDevices = showDevices(context, cmds);
		int selection = 0;
		IAroDevice device = null;
		// String selected = aroDevices.getId(3);
		if (aroDevices.size() > 1) {
			selection = -1;
			do {
				String range = "0-" + (aroDevices.size() - 1);
				String message = "Select a device, q to quit :" + range;
				String sValue = null;
				try {
					sValue = input(outSave, message, Pattern.compile("[" + range + "q]"));
					if (sValue.contains("q")){
						restoreSystemOut(outSave);
						System.exit(0);
					}
					selection = Integer.valueOf(sValue);
				} catch (NumberFormatException e) {
					outln("Illegal entry, unable to parse \"" + sValue + "\"");
				}
			} while (selection < 0 || selection >= aroDevices.size());
			
		} else if (aroDevices.size() == 1 && aroDevices.getDevice(0).getState().equals(AroDeviceState.Available)){
			selection = 0;
		} else {
			errln("No devices available");
			restoreSystemOut(outSave);
			System.exit(0);
		}
		
		// have a selected device
		device = aroDevices.getDevice(selection);
		
		cmds.setDeviceid(device.getId());
		
		// prepare collector choice
		String requestCollector = cmds.getAsk();
		if (cmds.getStartcollector() != null) {
			requestCollector = cmds.getStartcollector();
		}
		if (!collectorCompatibility(requestCollector, device)){
			outln("Error :Incompatible collector for device:"+device.getPlatform()+", collector:"+requestCollector);
			System.exit(0);
		}
		String deviceCollector = collectorSanityCheck(requestCollector, device);
		if(deviceCollector.startsWith("Error")){
			outln(deviceCollector);
			System.exit(0);
		}
		if ("auto".equals(requestCollector)) {
			// store the auto selection
			cmds.setStartcollector(deviceCollector);
		} else if (!requestCollector.equalsIgnoreCase(deviceCollector)){
			if (device.isRooted()) {
				// run rooted or vpn on rooted
				cmds.setStartcollector(requestCollector);
			} else if (!device.isRooted() && !requestCollector.equals("vpn_collector")) {
				// only run vpn on non-rooted
				cmds.setStartcollector(requestCollector);
			} else {
				outln("Error: incompatable collector for chosen device");
				System.exit(0);
			}
		} else {
			// allow the asked collector
			cmds.setStartcollector(requestCollector);
		}
	}

	/**
	 * Compares device with the requested collector
	 * 
	 * @param requestCollector
	 * @param device
	 * @return
	 */
	private boolean collectorCompatibility(String requestCollector, IAroDevice device) {
		Platform platform = device.getPlatform();
		switch (requestCollector) {
		case "rooted_android":
			if (device.isEmulator()) {
				return (device.getAbi().contains("arm"));
			}
		case "vpn_android":
			if (device.isEmulator()) {
				return (device.getAbi().contains("x86_64"));
			}
			return (!platform.equals(IAroDevice.Platform.iOS));
		case "ios":
			return (!platform.equals(IAroDevice.Platform.Android));
		case "auto":
			return true;
		default:
			break;
		}
		return false;
	}

	/**
	 * Performs a sanity check to guard against incompatible device/collector combinations.
	 * returns a collector that is compatible if there is a mismatch.
	 * 
	 * @param collector
	 * @param device
	 * @return collector that is compatible with device
	 */
	private String collectorSanityCheck(String collector, IAroDevice device) {
		String result = collector;

		switch (device.getPlatform()) {

		case iOS:
			result = "ios";
			break;
		case Android:
			if (device.isEmulator()) {
				if (device.getAbi().contains("arm")) {
					result = "rooted_android";
				} else if (device.getAbi().equals("x86")) {
					result = "Error: incompatable device, x86 Emulators are unsupported, use x86_64 or armeabi instead";
				} else {
					result = "vpn_android"; // default to VPN
				}
			} else {
				result = "vpn_android"; // default to VPN
			}
			break;

		default:
			break;
		}
		return result;
	}

	private void loadCommands(String[] args) {
		cmds = new Commands();
		try {
			new JCommander(cmds, args).setProgramName("aro");
		} catch (Exception ex) {
			System.err.print("Error parsing command: " + ex.getMessage());
			System.exit(1);
		}
	}

	private String input(OutSave outSave, String message, Pattern pattern) {
		String input = "";
		try {

			do {
				out(message);
				out(">");
				input = readInput();
			} while (!pattern.matcher(input).find());
		} finally {
			restoreSystemOut(outSave);
		}
		return input;
	}

	/**
	 * @param args
	 *            - see Help
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		new Application(args);
		System.exit(0);
	}

	/**
	 * Locates and displays any and all data collectors. Data collectors are jar
	 * files that allow controlling and collecting data on devices such as
	 * Android phone, tablets and emulators.
	 * 
	 * @param context
	 *            - Spring ApplicationContext
	 * @param cmds
	 *            - Not used
	 */
	void showCollector(ApplicationContext context, Commands cmds) {
		List<IDataCollector> list = getAvailableCollectors();
		if (list == null || list.size() < 1) {
			errln("No data collector found");
		} else {
			for (IDataCollector coll : list) {
				outln("-" + coll.getName() + " version: " + coll.getMajorVersion() + "." + coll.getMinorVersion());
			}
		}
	}

	/**
	 * Scans for and delivers an IAroDevices model
	 * 
	 * @param context
	 * @param cmds
	 * @return IAroDevices
	 */
	private IAroDevices showDevices(ApplicationContext context, Commands cmds) {

		outln("scanning devices...");

		IAroDevices aroDevices = aroController.getAroDevices();
		outln("list devices");
		if (aroDevices.size() > 0) {
			outln(aroDevices.toString());
		} else {
			outln(" No devices detected");
		}
		return aroDevices;
	}

	private OutSave prepareSystemOut() {
		OutSave outSave = new OutSave(System.out, Logger.getRootLogger().getLevel());
		if (utilOut.getThreshold().ordinal() < UtilOut.MessageThreshold.Verbose.ordinal()) {
			Logger.getRootLogger().setLevel(Level.WARN);
			System.setOut(new PrintStream(new NullOut()));
		}
		return outSave;
	}

	private void restoreSystemOut(OutSave outSave) {
		System.setOut(outSave.getOut());
		Logger.getRootLogger().setLevel(outSave.getLevel());
	}

	/**
	 * Analyze a trace and produce a report either in json or html<br>
	 * 
	 * <pre>
	 * Required command:
	 *   --analyze with path to trace directory of traffic.cap
	 *   --output output file, error if missing
	 *   --format html or json, if missing defaults to json
	 * 
	 * @param context - Spring ApplicationContext
	 * @param cmds - user commands
	 */
	void runAnalyzer(ApplicationContext context, Commands cmds) {

		String trace = cmds.getAnalyze();
		IAROService serv = context.getBean(IAROService.class);
		AROTraceData results = null;

		// analyze trace file or directory?
		OutSave outSave = prepareSystemOut();
		ImHereThread imHereThread = new ImHereThread(outSave.getOut(), Logger.getRootLogger());
		try {
			if (serv.isFile(trace)) {
				try {
					results = serv.analyzeFile(getBestPractice(), trace);
				} catch (IOException e) {
					errln("Error occured analyzing trace, detail: " + e.getMessage());
					System.exit(1);
				}
			} else {
				try {
					results = serv.analyzeDirectory(getBestPractice(), trace);
				} catch (IOException e) {
					errln("Error occured analyzing trace directory, detail: " + e.getMessage());
					System.exit(1);
				}
			}

			if (results.isSuccess()) {
				outSave = prepareSystemOut();
				if (cmds.getFormat().equals("json")) {
					if (serv.getJSonReport(cmds.getOutput(), results)) {
						outln("Successfully produced JSON report: " + cmds.getOutput());
					} else {
						errln("Failed to produce JSON report.");
					}
				} else {
					if (serv.getHtmlReport(cmds.getOutput(), results)) {
						outln("Successfully produced HTML report: " + cmds.getOutput());
					} else {
						errln("Failed to produce HTML report.");
					}
				}
			} else {
				printError(results.getError());
			}
		} finally {
			imHereThread.endIndicator();
			while (imHereThread.isRunning()) {
				Thread.yield();
			}
			restoreSystemOut(outSave);
		}
		System.exit(1);
	}
	
	private VideoOption getVideoOption() {

		switch (cmds.getVideo()) {
		case "yes":		// default to original 
		case "slow":    return VideoOption.LREZ;
		case "hd":      return VideoOption.HDEF;
		case "sd":      return VideoOption.SDEF;
		case "no":      return VideoOption.NONE;
		default:
			return VideoOption.NONE;
		}
	}
	
	private int getDelayTimeUplink() {
		return cmds.getUplink();
	}
	
	private int getDelayTimeDownlink() {
		return cmds.getDownlink();
	}
	
	private boolean getSecureOption() {
		return cmds.isSecure();
	}
	
	private boolean getCertInstallOption() {
		return cmds.isCertInstall();
	}
	
	void printError(ErrorCode error) {
		err("Error code: " + error.getCode());
		err(", Error name: " + error.getName());
		errln(", Error description: " + error.getDescription());
	}

	/**
	 * Launches a DataCollection. Provides an input prompt for the user to stop
	 * the collection by typing "stop"
	 * 
	 * <pre>
	 * Note:
	 * Do not exit collection by pressing a ctrl-c
	 * Doing so will exit ARO.Console but will not stop the trace on the device.
	 * </pre>
	 * 
	 * @param context
	 * @param cmds
	 */
	void runDataCollector(ApplicationContext context, Commands cmds) {
		if (cmds.getOutput() != null) {
			// LOGGER.info("runDataCollector");
			IDataCollectorManager colmg = context.getBean(IDataCollectorManager.class);
			colmg.getAvailableCollectors(context);
			IDataCollector collector = null;
			
			switch (cmds.getStartcollector()) {
			case "rooted_android":
				collector = colmg.getRootedDataCollector();
				break;
			case "vpn_android":
				collector = colmg.getNorootedDataCollector();
				break;
			case "ios":
				collector = colmg.getIOSCollector();
				break;
			default:
				printError(ErrorCodeRegistry.getCollectorNotfound());
				System.exit(1);
				break;
			}
			
			String password = cmds.getSudo();
			if (password!=null){
				
			}
			
			StatusResult result = null;
			if (collector==null){
				printError(ErrorCodeRegistry.getCollectorNotfound());
				System.exit(1);
			}
			if (cmds.getOverwrite().equalsIgnoreCase("yes")) {
				String traceName = cmds.getOutput();
				IFileManager filemanager = context.getBean(IFileManager.class);
				boolean r = filemanager.directoryDeleteInnerFiles(traceName);
			}
			OutSave outSave = prepareSystemOut();
			try {			
				Hashtable<String,Object> extras = new Hashtable<String,Object>();
				extras.put("video_option", getVideoOption());
				if (cmds.getDeviceid() != null) {
					result = collector.startCollector(true, cmds.getOutput(), getVideoOption(), false, cmds.getDeviceid(), extras, password);
				} else {
					result = collector.startCollector(true, cmds.getOutput(), getVideoOption(), false, null, extras, password);
				}
			} finally {
				restoreSystemOut(outSave);
			}

			if (result.getError() != null) {
				outln("Caught an error:");
				printError(result.getError());
			} else {

				outSave = prepareSystemOut();
				try {
					String input = "";
					do {
						out("Data collector is running, enter stop to save trace and quit program");
						out(">");
						input = readInput();
					} while (!input.contains("stop"));
				} finally {
					restoreSystemOut(outSave);
				}

				outln("stopping collector...");
				try {
					collector.stopCollector();
				} finally {
					restoreSystemOut(outSave);
				}
				outln("collector stopped, trace saved to: " + cmds.getOutput());

				cleanUp(context);
				outln("ARO exited");
				System.exit(1);
			}
		}else{
			outln("No output tracefolder was entered\n");
			usageHelp();
			System.exit(1);
		}
	}
	
	/**
	 * Provides for user input
	 * 
	 * @return user input
	 */
	String readInput() {
		BufferedReader bufferRead = new BufferedReader(new InputStreamReader(System.in));
		try {
			return bufferRead.readLine();
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * print string to console with new line char
	 * 
	 * @param str
	 *            - output text
	 */
	void out(String str) {
		utilOut.outMessage(str, UtilOut.MessageThreshold.Normal);
	}

	void outln(String str) {
		utilOut.outMessageln(str, UtilOut.MessageThreshold.Normal);
	}

	void err(String str) {
		utilOut.errMessage(str);
	}

	void errln(String str) {
		utilOut.errMessageln(str);
	}

	/**
	 * return a list of best practice we want to run. the sequence is according
	 * to the Analyzer
	 * 
	 * @return a list of best practice
	 */
	private List<BestPracticeType> getBestPractice() {
		List<BestPracticeType> req = new ArrayList<BestPracticeType>();
		req.add(BestPracticeType.FILE_COMPRESSION);
		req.add(BestPracticeType.DUPLICATE_CONTENT);
		req.add(BestPracticeType.USING_CACHE);
		req.add(BestPracticeType.CACHE_CONTROL);
		req.add(BestPracticeType.COMBINE_CS_JSS);
		req.add(BestPracticeType.IMAGE_SIZE);
		req.add(BestPracticeType.IMAGE_MDATA);
		req.add(BestPracticeType.IMAGE_CMPRS);
		req.add(BestPracticeType.MINIFICATION);
		req.add(BestPracticeType.SPRITEIMAGE);
		req.add(BestPracticeType.CONNECTION_OPENING);
		req.add(BestPracticeType.UNNECESSARY_CONNECTIONS);
		req.add(BestPracticeType.PERIODIC_TRANSFER);
		req.add(BestPracticeType.SCREEN_ROTATION);
		req.add(BestPracticeType.CONNECTION_CLOSING);
		req.add(BestPracticeType.HTTP_4XX_5XX);
		req.add(BestPracticeType.HTTP_3XX_CODE);
		req.add(BestPracticeType.SCRIPTS_URL);
		req.add(BestPracticeType.ASYNC_CHECK);
		req.add(BestPracticeType.HTTP_1_0_USAGE);
		req.add(BestPracticeType.FILE_ORDER);
		req.add(BestPracticeType.EMPTY_URL);
		req.add(BestPracticeType.FLASH);
		req.add(BestPracticeType.DISPLAY_NONE_IN_CSS);
		req.add(BestPracticeType.HTTPS_USAGE);
		req.add(BestPracticeType.TRANSMISSION_PRIVATE_DATA);
		req.add(BestPracticeType.UNSECURE_SSL_VERSION);
		req.add(BestPracticeType.WEAK_CIPHER);
		req.add(BestPracticeType.FORWARD_SECRECY);
		req.add(BestPracticeType.VIDEO_STALL);
		req.add(BestPracticeType.NETWORK_COMPARISON);
		req.add(BestPracticeType.STARTUP_DELAY);
		req.add(BestPracticeType.BUFFER_OCCUPANCY);
		req.add(BestPracticeType.TCP_CONNECTION);
		req.add(BestPracticeType.CHUNK_PACING);
		req.add(BestPracticeType.CHUNK_SIZE);
		req.add(BestPracticeType.VIDEO_REDUNDANCY);
		req.add(BestPracticeType.ACCESSING_PERIPHERALS);
 
		return req;
	}

	/**
	 * Displays user help
	 */
	private void usageHelp() {
		StringBuilder sbuilder = new StringBuilder(1000);
		sbuilder.append("Version:")
				.append(buildBundle.getString("build.majorversion"))
				.append(".")
				.append(buildBundle.getString("build.timestamp"))
		
				.append("\nUsage: vo [commands] [arguments]")
				.append("\n  --analyze [trace location]: analyze a trace folder or file.")
				.append("\n  --startcollector [rooted_android|vpn_android|ios]: run a collector.")
				.append("\n  --ask [auto|rooted_android|vpn_android|ios]: asks for a device then runs the collector.")
				.append("\n  --output [fullpath including filename] : output to a file or trace folder")
				.append("\n  --overwrite [yes/no] : overwrite a trace folder")
				.append("\n  --deviceid [device id]: optional device id of Android or Serial Number for IOS.")
				.append("\n    If not delcared first device found is used.")
				.append("\n  --format [json|html]: optional type of report to generate. Default: json.")
				.append(
						(Util.isMacOS())
						?"\n  --video [hd|sd|slow|no]: optional command to record video when running collector. Default: no."
						:"\n  --video [yes|no]: optional command to record video when running collector. Default: no."
						)
				.append("\n  --secure: optional command to enable secure collector.")
				.append("\n  --certInstall: optional command to install certificate if secure collector is enabled.")
				.append("\n  --uplink [number in millisecond]: optional command for uplink delay, range from 0 to 100 millisecond.")
				.append("\n  --downlink [number in millisecond]: optional command for downlink delay, range from 0 to 2000 millisecond.")
				.append("\n  --listcollectors: optional command to list available data collector.")
				.append("\n  --verbose:  optional command to enables detailed messages for '--analyze' and '--startcollector'")
				.append("\n  --help,-h,-?: show help menu.")
				.append("\n\nUsage examples: ")
				.append("\n=============")
				.append("\nRun Android collector to capture trace with video:")
				.append("\n    trace will not be overwritten if it exits: ")
				.append("\n    slow video is 1-2 frames per second: ")
				.append("\n  --startcollector rooted_android --output /User/documents/test --video slow")
				
				.append("\nRun Non-rooted Android collector to capture trace with video using secure collector:")
				.append("\n    --certInstall option requires --secure option to be enabled")
				.append("\n  --startcollector vpn_android --output /User/documents/test --video slow --secure --certInstall")
				
				.append("\nRun Non-rooted Android collector to capture trace with video and uplink/downlink attenuation applied:")
				.append("\n    uplink can accept 0 - 100 milliseconds delay")
				.append("\n    downlink can accept 0 - 2000 milliseconds delay")
				.append("\n  --startcollector vpn_android --output /User/documents/test --video slow --uplink 55 --downlink 1400")
				
				.append("\nRun iOS collector to capture trace with video: ")
				.append("\n    trace will be overwritten if it exits: ")
				.append("\n  --startcollector ios --overwrite yes --output /Users/{user}/tracefolder --video hd --sudo password")
				
				.append("\nAsk user for device and Run Android collector to capture trace with video: ")
				.append("\n  --ask rooted_android --output /User/documents/test --video sd")
				
				.append("\nAsk for device and Run iOS collector to capture trace with video: ")
				.append("\n  --ask ios --output /Users/{user}/tracefolder --video slow --sudo password")
				
				.append("\nAsk for device and Run appropriate collector for device to capture trace with video: ")
				.append("\n    Note: --sudo is not required or ignored for Android")
				.append("\n  --ask auto --output /Users/{user}/tracefolder --video slow --sudo password")
				
				.append("\nAnalyze trace and produce HTML report")
				.append("\n  --analyze /User/documents/test --output /User/documents/report.html --format html")
				
				.append("\nAnalyze trace and produce JSON report:")
				.append("\n  --analyze /User/documents/test/traffic.cap --output /User/documents/report.json");
		outln(sbuilder.toString());
	}

	private void cleanUp(ApplicationContext context) {
		String dir = "";
		File filepath = new File(UtilOut.class.getProtectionDomain().getCodeSource().getLocation().getPath());
		dir = filepath.getParent();
		IFileManager filemanager = context.getBean(IFileManager.class);
		filemanager.deleteFile(dir + System.getProperty("file.separator") + "AROCollector.apk");
		filemanager.deleteFile(dir + System.getProperty("file.separator") + "ARODataCollector.apk");
	}

	// ------------------------------------------------------------------------------------------------------------------
	// IAROView
	// ------------------------------------------------------------------------------------------------------------------

	@Override
	public void updateTracePath(File path) {
		LOGGER.info(path);
	}

	@Override
	public void updateProfile(Profile profile) {
		LOGGER.info("updateProfile:" + profile);
	}

	@Override
	public void updateReportPath(File path) {
		LOGGER.info("updateReportPath:" + path);

	}

	@Override
	public void updateFilter(AnalysisFilter filter) {
		LOGGER.info("updateFilter:" + filter);
	}

	@Override
	public String getTracePath() {
		return null;
	}

	@Override
	public String getReportPath() {
		return null;
	}

	@Override
	public void addAROPropertyChangeListener(PropertyChangeListener listener) {
	}

	@Override
	public void addAROActionListener(ActionListener listener) {
	}

	@Override
	public void refresh() {
	}

	@Override
	public void startCollector(IAroDevice device, String tracePath, Hashtable<String, Object> extraParams) {
	}
	
	@Override
	public void startCollectorIos(IDataCollector iOsCollector, String udid, String tracePath, VideoOption videoOption) {
	}

	@Override
	public void stopCollector() {
	}
	
	@Override
	public void cancelCollector() {
	}

	@Override
	public void haltCollector() {
	}

	@Override
	public IDevice[] getConnectedDevices() {
		return null;
	}

	@Override
	public IAroDevices getAroDevices() {
		return null;
	}

	@Override
	public List<IDataCollector> getAvailableCollectors() {
		return collectorList;
	}

	@Override
	public void updateCollectorStatus(CollectorStatus status, StatusResult result) {
		LOGGER.info("updateCollectorStatus:" + status + ", result:" + result.isSuccess());
	}

	@Override
	public CollectorStatus getCollectorStatus() {
		return null;
	}

	@Override
	public void liveVideoDisplay(IDataCollector collector) {
	}
	
	@Override
	public void hideAllCharts(){
	}
	
	@Override
	public void showAllCharts(){
	}

}