package nabu.misc.sound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

import javax.jws.WebParam;
import javax.jws.WebService;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.DataLine.Info;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

@WebService
public class Services {
	
	private static ByteArrayOutputStream output;
	private static Thread recorder, stopper;
	
	private AudioFormat getBestFormat(int minChannels) {
		Line.Info[] lineInfos = AudioSystem.getTargetLineInfo(new Line.Info(TargetDataLine.class));
		AudioFormat bestMatch = null;
		for (Line.Info pi : lineInfos) {
			for (AudioFormat possible : ((TargetDataLine.Info) pi).getFormats()) {
				if (bestMatch == null) {
					bestMatch = possible;
				}
				else if (possible.getChannels() >= minChannels) {
					// ulaw is preferred
					if (possible.getEncoding() == AudioFormat.Encoding.ULAW && bestMatch.getEncoding() != AudioFormat.Encoding.ULAW) {
						bestMatch = possible;
					}
					// as few channels as possible is better
					else if (possible.getChannels() < bestMatch.getChannels()) {
						bestMatch = possible;
					}
				}
			}
		}
		return bestMatch;
	}
	
	public void record(@WebParam(name = "channels") Integer channels) throws LineUnavailableException {
		if (channels == null) {
			channels = 1;
		}
		
		float sampleRate = 16000;
		int sampleSizeInBits = 16;
		boolean signed = true;
		boolean bigEndian = true;
		
		int frameSize = 2;
		final AudioFormat format = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
		for (Encoding encoding : AudioSystem.getTargetEncodings(format)) {
			System.out.println("possible encoding: " + encoding);
		}
//				final AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, sampleSizeInBits, channels, frameSize, sampleRate, bigEndian);
		final AudioFormat targetFormat = new AudioFormat(AudioFormat.Encoding.ULAW, sampleRate, sampleSizeInBits, channels, frameSize, sampleRate, false);

		System.out.println("Chosen format is: " + format);
		
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
		if (!AudioSystem.isLineSupported(info)) {
			throw new RuntimeException("Recording is not supported for the given format: " + format);
		}
//		final TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
		final TargetDataLine line = AudioSystem.getTargetDataLine(format);
	    line.open(format);
	    
		output = new ByteArrayOutputStream();
		recorder = new Thread(new Runnable() {
			@Override
			public void run() {
				int read;
				byte[] data = new byte[line.getBufferSize() / 5];
				line.start();
				while ((read = line.read(data, 0, data.length)) > 0) {
					output.write(data, 0, read);
					System.out.println("Wrote: " + read);
				}
				byte[] bytes = output.toByteArray();
				System.out.println("Recorded: " + bytes.length);
				AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(bytes), format, bytes.length / format.getFrameSize());
				
//				AudioInputStream converted = AudioSystem.getAudioInputStream(targetFormat, input);
				output = new ByteArrayOutputStream();
				try {
					AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
				}
				catch (IOException e) {
					output = null;
					e.printStackTrace();
					throw new RuntimeException(e);
				}
			}
		});
		recorder.start();
		stopper = new Thread(new Runnable() {
			@Override
			public void run() {
				line.stop();
				line.drain();				
				line.close();
			}
		});
	}
	
	public boolean isRecording() {
		return recorder != null;
	}
	
	public byte[] stopRecording() throws InterruptedException, UnsupportedAudioFileException, IOException {
		if (recorder != null) {
			stopper.start();
			// wait for max 10 seconds to finish
			recorder.join(1000l*10);
			recorder = null;
			if (output == null) {
				throw new IllegalStateException("No output found");
			}
			return output.toByteArray();
		}
		return null;
	}
	
	public void play(@WebParam(name = "audio") InputStream content) throws LineUnavailableException, IOException, UnsupportedAudioFileException {
		AudioInputStream audioIn = AudioSystem.getAudioInputStream(content);
		Info info = new DataLine.Info(Clip.class, audioIn.getFormat());
//		Clip clip = AudioSystem.getClip();
		Clip clip = (Clip) AudioSystem.getLine(info);
		clip.open(audioIn);
		clip.start();
	}
	
	/**
	 * The audio API needs to know the size of the wav file to play, but because watson streams it, it doesn't know the size. Without it however you get the following exception:
	 * Caused by: com.sun.media.sound.RIFFInvalidDataException: Chunk size too big
			at com.sun.media.sound.RIFFReader.<init>(RIFFReader.java:82)
			at com.sun.media.sound.WaveFloatFileReader.internal_getAudioFileFormat(WaveFloatFileReader.java:65)
			at com.sun.media.sound.WaveFloatFileReader.getAudioFileFormat(WaveFloatFileReader.java:55)
			at com.sun.media.sound.WaveFloatFileReader.getAudioInputStream(WaveFloatFileReader.java:117)
			at javax.sound.sampled.AudioSystem.getAudioInputStream(AudioSystem.java:1113)
			at nabu.misc.sound.Services.play(Services.java:18)
			... 35 more (no bundle: exceptions)
	 * 
	 * 
	 * According to https://github.com/watson-developer-cloud/java-sdk/issues/81 the fix is https://github.com/watson-developer-cloud/java-sdk/commit/520a7794ee4448a70a7429056101e076071d329b
	 * where we basically rewrite the size of the wav file base
	 
	 * based on: https://github.com/watson-developer-cloud/java-sdk/blob/520a7794ee4448a70a7429056101e076071d329b/src/main/java/com/ibm/watson/developer_cloud/text_to_speech/v1/util/WaveUtils.java
	 * both an old version and a new version (check the diff in the commit)
	 */
	public void updateWavLength(@WebParam(name = "audio") byte [] content) {
		int filesize = content.length - 8;
		writeInt(filesize, content, 4);
		
		String type = new String(Arrays.copyOfRange(content, 36, 40), Charset.forName("ASCII"));
		if (type.equalsIgnoreCase("data")) {
			writeInt(content.length - 44, content, 40);
		}
		else if (type.equalsIgnoreCase("list")) {
			writeInt(filesize - 8, content, 74);
		}
	}
	
	private static void writeInt(int value, byte[] array, int offset) {
		for (int i = 0; i < 4; i++) {
			array[offset + i] = (byte) (value >>> (8 * i));
		}
	}
}
