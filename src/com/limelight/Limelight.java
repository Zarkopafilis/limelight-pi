package com.limelight;

import java.io.IOException;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.FakeAudioRenderer;
import com.limelight.binding.video.FakeVideoRenderer;
import com.limelight.input.EvdevLoader;
import com.limelight.input.GamepadMapping;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.NvHTTP;
import java.io.File;
import java.io.FileNotFoundException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.limelight.nvstream.http.PairingManager;

/**
 * Main class for Limelight Pi
 * @author Diego Waxemberg<br>
 * Cameron Gutman
 * Iwan Timmer
 */
public class Limelight implements NvConnectionListener {

	private String host;
	private NvConnection conn;
	private boolean connectionTerminating;

	/**
	 * Constructs a new instance based on the given host
	 * @param host can be hostname or IP address.
	 */
	public Limelight(String host) {
		this.host = host;
	}

	/*
	 * Creates a connection to the host and starts up the stream.
	 */
	private void startUp(StreamConfiguration streamConfig, List<String> inputs, String mappingFile, String audioDevice, boolean tests) {
		if (tests) {
			boolean test = true;
			String vm = System.getProperties().getProperty("java.vm.name");
			if (!vm.contains("HotSpot")) {
				System.err.println("You are using a unsupported VM: " + vm);
				System.err.println("Please update to Oracle Java (Embedded) for better performances");
				test = false;
			}
			String display = System.getenv("DISPLAY");
			if (display!=null) {
				System.err.println("X server is propably running");
				System.err.println("Please exit the X server for a lower latency");
				test = false;
			}
			
			if (!test) {
				System.err.println("Fix problems or start application with parameter -notest");
				return;
			}
		}
	
		conn = new NvConnection(host, this, streamConfig, PlatformBinding.getCryptoProvider());
		
		GamepadMapping mapping = null;
		if (mappingFile!=null) {
			try {
				mapping = new GamepadMapping(new File(mappingFile));
			} catch (IOException e) {
				displayError("Mapping", "Can't load gamepad mapping from " + mappingFile);
				System.exit(3);
			}
		} else
			mapping = new GamepadMapping();
		
		try {
			new EvdevLoader(conn, mapping, inputs).start();
		} catch (FileNotFoundException ex) {
			displayError("Input", "Input could not be found");
			return;
		} catch (IOException ex) {
			displayError("Input", "No input could be readed");
			displayError("Input", "Try to run as root");
			return;
		}

		conn.start(PlatformBinding.getDeviceName(), null,
				VideoDecoderRenderer.FLAG_PREFER_QUALITY,
				PlatformBinding.getAudioRenderer(audioDevice),
				PlatformBinding.getVideoDecoderRenderer());
	}
	
	/*
	 * Creates a connection to the host and starts up the stream.
	 */
	private void startUpFake(StreamConfiguration streamConfig, String videoFile) {
		conn = new NvConnection(host, this, streamConfig, PlatformBinding.getCryptoProvider());
		conn.start(PlatformBinding.getDeviceName(), null,
				VideoDecoderRenderer.FLAG_PREFER_QUALITY,
				new FakeAudioRenderer(),
				new FakeVideoRenderer(videoFile));
	}
	
	/**
	 * Pair the device with the host
	 */
	private void pair() {
		String macAddress;
		try {
			macAddress = NvConnection.getMacAddressString();
		} catch (SocketException e) {
			e.printStackTrace();
			return;
		}

		if (macAddress == null) {
			displayError("Pair", "Couldn't find a MAC address");
			return;
		}

		NvHTTP httpConn;
	
		try {
			httpConn = new NvHTTP(InetAddress.getByName(host),
				macAddress, PlatformBinding.getDeviceName(), PlatformBinding.getCryptoProvider());
			try {
				if (httpConn.getPairState() == PairingManager.PairState.PAIRED) {
					displayError("pair", "Already paired");
				} else {
					final String pinStr = PairingManager.generatePinString();
					
					displayMessage("Please enter the following PIN on the target PC: "+pinStr);
					
					PairingManager.PairState pairState = httpConn.pair(pinStr);
					if (pairState == PairingManager.PairState.PIN_WRONG) {
						displayError("pair", "Incorrect PIN");
					}
					else if (pairState == PairingManager.PairState.FAILED) {
						displayError("pair", "Pairing failed");
					}
					else if (pairState == PairingManager.PairState.PAIRED) {
						displayError("pair", "Paired successfully");
					}
				}
			} catch (Exception e) {
				displayError("Pair", e.getMessage());
			}
		} catch (UnknownHostException e1) {
			displayError("Pair", "Failed to resolve host");
		}
	}
	
	/**
	 * The entry point for the application. <br>
	 * Does some initializations and then creates the main frame.
	 * @param args unused.
	 */
	public static void main(String args[]) {
		String host = null;
		List<String> inputs = new ArrayList<String>();
		boolean pair = false;
		int width = 1280;
		int height = 720;
		int refresh = 60;
		int bitrate = 10000;
		int packetSize = 1024;
		boolean parse = true;
		boolean fake = false;
		boolean tests = true;
		String mapping = null;
		String audio = "default";
		String video = null;
		Level debug = Level.SEVERE;
		
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-input")) {
				if (i + 1 < args.length) {
					inputs.add(args[i+1]);
					i++;
				} else {
					System.out.println("Syntax error: input device expected after -input");
					System.exit(3);
				}
			} else if (args[i].equals("-mapping")) {
				if (i + 1 < args.length) {
					mapping = args[i+1];
					i++;
				} else {
					System.out.println("Syntax error: mapping file expected after -mapping");
					System.exit(3);
				}
			} else if (args[i].equals("-audio")) {
				if (i + 1 < args.length) {
					audio = args[i+1];
					i++;
				} else {
					System.out.println("Syntax error: audio device expected after -audio");
					System.exit(3);
				}
			} else if (args[i].equals("-pair")) {
				pair = true;
			} else if (args[i].equals("-720")) {
				height = 720;
				width = 1280;
			} else if (args[i].equals("-1080")) {
				height = 1080;
				width = 1920;
			} else if (args[i].equals("-width")) {
				if (i + 1 < args.length) {
					try {
						width = Integer.parseInt(args[i+1]);
					} catch (NumberFormatException e) {
						System.out.println("Syntax error: width must be a number");
						System.exit(3);
					}
					i++;
				} else {
					System.out.println("Syntax error: width expected after -width");
					System.exit(3);
				}
			} else if (args[i].equals("-height")) {
				if (i + 1 < args.length) {
					try {
						height = Integer.parseInt(args[i+1]);
					} catch (NumberFormatException e) {
						System.out.println("Syntax error: height must be a number");
						System.exit(3);
					}
					i++;
				} else {
					System.out.println("Syntax error: height expected after -height");
					System.exit(3);
				}
			} else if (args[i].equals("-30fps")) {
				refresh = 30;
			} else if (args[i].equals("-60fps")) {
				refresh = 60;
			} else if (args[i].equals("-bitrate")) {
				if (i + 1 < args.length) {
					try {
						bitrate = Integer.parseInt(args[i+1]);
					} catch (NumberFormatException e) {
						System.out.println("Syntax error: bitrate must be a number");
						System.exit(3);
					}
					i++;
				} else {
					System.out.println("Syntax error: bitrate expected after -bitrate");
					System.exit(3);
				}
			} else if (args[i].equals("-packetsize")) {
				if (i + 1 < args.length) {
					try {
						packetSize = Integer.parseInt(args[i+1]);
					} catch (NumberFormatException e) {
						System.out.println("Syntax error: packetsize must be a number");
						System.exit(3);
					}
					i++;
				} else {
					System.out.println("Syntax error: packetsize expected after -packetsize");
					System.exit(3);
				}
			} else if (args[i].equals("-fake")) {
				fake = true;
			} else if (args[i].equals("-out")) {
				if (i + 1 < args.length) {
					video = args[i+1];
					i++;
				} else {
					System.out.println("Syntax error: output file expected after -out");
					System.exit(3);
				}
			} else if (args[i].equals("-notest")) {
				tests = false;
			} else if (args[i].equals("-v")) {
				debug = Level.WARNING;
			} else if (args[i].equals("-vv")) {
				debug = Level.ALL;
			} else if (args[i].startsWith("-")) {
				System.out.println("Syntax Error: Unrecognized argument: " + args[i]);
				parse = false;
			} else if (host == null) {
				host = args[i];
			} else {
				System.out.println("Syntax Error: Unrecognized argument: " + args[i]);
				parse = false;
			}
		}
		
		if (host == null) {
			System.out.println("Syntax Error: Missing required host argument");
			parse = false;
		}
		
		if (args.length == 0 || !parse) {
			System.out.println("Usage: java -jar limelight-pi.jar [options] host");
			System.out.println("\t-720\t\tUse 1280x720 resolution [default]");
			System.out.println("\t-1080\t\tUse 1920x1080 resolution");
			System.out.println("\t-width <width>\tHorizontal resolution (default 1280)");
			System.out.println("\t-height <height>\tVertical resolution (default 720)");
			System.out.println("\t-30fps\t\tUse 30fps");
			System.out.println("\t-60fps\t\tUse 60fps [default]");
			System.out.println("\t-bitrate <bitrate>\t\tSpecify the bitrate in Kbps");
			System.out.println("\t-packetsize <size>\t\tSpecify the packetsize in bytes");
			System.out.println("\t-input <device>\tUse <device> as input. Can be used multiple times");
			System.out.println("\t\t\t[default uses all devices in /dev/input]");
			System.out.println("\t-mapping <file>\tUse <file> as gamepad mapping configuration file");
			System.out.println("\t-audio <device>\tUse <device> as ALSA audio output device (default hw:0)");
			System.out.println("\t-pair\t\tPair with host");
			System.out.println();
			System.out.println("Use ctrl-c to exit application");
			System.exit(5);
		}
		
		//Set debugging level
		Logger.getLogger(LimeLog.class.getName()).setLevel(debug);
		
		StreamConfiguration streamConfig = new StreamConfiguration(width, height, refresh, bitrate, packetSize);
		
		Limelight limelight = new Limelight(host);
		if (!pair)
			if (fake)
				limelight.startUpFake(streamConfig, video);
			else
				limelight.startUp(streamConfig, inputs, mapping, audio, tests);
		else
			limelight.pair();
	}
	
	
	public void stop() {
		connectionTerminating = true;
		conn.stop();
	}

	/**
	 * Callback to specify which stage is starting. Used to update UI.
	 * @param stage the Stage that is starting
	 */
	@Override
	public void stageStarting(Stage stage) {
		System.out.println("Starting "+stage.getName());
	}

	/**
	 * Callback that a stage has finished loading.
	 * <br><b>NOTE: Currently unimplemented.</b>
	 * @param stage the Stage that has finished.
	 */
	@Override
	public void stageComplete(Stage stage) {
	}

	/**
	 * Callback that a stage has failed. Used to inform user that an error occurred.
	 * @param stage the Stage that was loading when the error occurred
	 */
	@Override
	public void stageFailed(Stage stage) {
		conn.stop();
		displayError("Connection Error", "Starting " + stage.getName() + " failed");
	}

	/**
	 * Callback that the connection has finished loading and is started.
	 */
	@Override
	public void connectionStarted() {
	}

	/**
	 * Callback that the connection has been terminated for some reason.
	 * <br>This is were the stream shutdown procedure takes place.
	 * @param e the Exception that was thrown- probable cause of termination.
	 */
	@Override
	public void connectionTerminated(Exception e) {
		if (!(e instanceof InterruptedException)) {
			e.printStackTrace();
		}
		if (!connectionTerminating) {
			connectionTerminating = true;

			// Kill the connection to the target
			conn.stop();

			// Spin off a new thread to update the UI since
			// this thread has been interrupted and will terminate
			// shortly
			new Thread(new Runnable() {
				@Override
				public void run() {
					displayError("Connection Terminated", "The connection failed unexpectedly");
				}
			}).start();
		}
	}

	/**
	 * Displays a message to the user in the form of an info dialog.
	 * @param message the message to show the user
	 */
	@Override
	public void displayMessage(String message) {
		System.out.println(message);
	}	

	/**
	 * Displays an error to the user in the form of an error dialog
	 * @param title the title for the dialog frame
	 * @param message the message to show the user
	 */
	public void displayError(String title, String message) {
		System.err.println(title + " " + message);
	}

	@Override
	public void displayTransientMessage(String message) {
		displayMessage(message);
	}
}

