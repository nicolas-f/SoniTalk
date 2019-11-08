/*
 * Copyright (c) 2019. Alexis Ringot, Florian Taurer, Matthias Zeppelzauer.
 *
 * This file is part of SoniTalk Android SDK.
 *
 * SoniTalk Android SDK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SoniTalk Android SDK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with SoniTalk Android SDK.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.ac.fhstp.sonitalk;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.support.annotation.IntDef;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import at.ac.fhstp.sonitalk.exceptions.DecoderStateException;
import at.ac.fhstp.sonitalk.utils.CRC;
import at.ac.fhstp.sonitalk.utils.CircularArray;

import org.noise_planet.jwarble.Configuration;
import org.noise_planet.jwarble.MessageCallback;
import org.noise_planet.jwarble.OpenWarble;

/**
 * Handles the capture of audio, the detection of messages and their decoding. The receiveBackground
 * functions execute in a worker Thread and need to be stopped when your application stops. Please
 * call stopReceiving() when you are done with receiving to release the resources (e.g. microphone access)
 */
public class SoniTalkDecoder implements MessageCallback {
    private static final String TAG = SoniTalkDecoder.class.getSimpleName();
    private final SoniTalkContext soniTalkContext;
    private DecoderThread decoderThread;
    // Define the list of accepted constants for DecoderState annotation
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_INITIALIZED, STATE_LISTENING, STATE_CANCELLED, STATE_STOPPED})
    /*package-private*/ @interface DecoderState {}

    /**
     * Interface defining the callbacks to implement in order to receive messages from a SoniTalk Decoder.
     */
    public interface MessageListener {
        /**
         * Called when a message is received by the SoniTalkDecoder.
         * @param receivedMessage message detected and received by the SDK.
         */
        void onMessageReceived(SoniTalkMessage receivedMessage);

        /**
         * Called when an error and/or exception occur in the SoniTalkDecoder.
         * @param errorMessage description of an error that occurred while trying to receive a message.
         */
        void onDecoderError(String errorMessage);
    }

    @Override
    public void onNewMessage(byte[] payload, long sampleId) {
        notifyMessageListeners(new SoniTalkMessage(payload, true, (int)((sampleId / (double)Fs)*1e6)));
    }

    @Override
    public void onPitch(long sampleId) {

    }

    @Override
    public void onError(long sampleId) {
        notifyMessageListenersOfError("Cannot repair message");
    }

    /**
     * Interface defining the callbacks to implement in order to receive the spectrum of received messages.
     */
    public interface SpectrumListener {
        /**
         * Called when a message is received by the SoniTalkDecoder. Contains the spectrum as a two
         * dimensional array that can then be visualized for further analysis of the detection.
         * @param spectrum two dimensional array containing energy levels for each frequency.
         * @param crcIsCorrect true if no error was detected by the CRC checksum.
         */
        void onSpectrum(float[][] spectrum, boolean crcIsCorrect);
    }

    // DecoderState constants
    /*package-private*/ static final int STATE_INITIALIZED = 0;
    /*package-private*/ static final int STATE_LISTENING = 1;
    /*package-private*/ static final int STATE_CANCELLED = 2;
    /*package-private*/ static final int STATE_STOPPED = 3;

    private List<MessageListener> messageListeners = new ArrayList<>();
    private List<SpectrumListener> spectrumListeners = new ArrayList<>();

    private AudioRecord audioRecorder;

    // Needed for the encoder part
    private int bitperiodInSamples;
    private int pauseperiodInSamples;

    private boolean silentMode = false;// Skips the viz ?
    private boolean returnRawAudio = false;

    // AudioRecord doc says: "The sample rate expressed in Hertz. 44100Hz is currently the only rate that is guaranteed to work on all devices"
    private int Fs; // Should always be larger than two times the f0

    // Profile
    private SoniTalkConfig config;

    // Recognition parameter
    private double startFactor;// = 2.0;
    private double endFactor;// = 2.0;
    private int bandPassFilterOrder;// = 8;
    private int stepFactor;// = 8;

    private int nNeighborsFreqUpDown = 1;
    private int nNeighborsTimeLeftRight = 1;
    private String aggFcn = "median";

    private int requestCode;


    /**
     * nBlocks refers to the previous naming. It corresponds to 2 + (nMessageBlocks * 2)
     * The current specification groups the two "blocks" of each bit (hence the     * 2).
     */
    private int nBlocks;
    private int frequencies[];
    private int bandpassWidth;// = frequencySpace*(nFrequencies/2);
    private int winLenForSpectrogram;
    private int winLenForSpectrogramInSamples ;
    private int frequencyOffsetForSpectrogram;// = 50;

    private int analysisWinLen;
    private int analysisWinStep;
    private int nAnalysisWindowsPerBit;
    private int nAnalysisWindowsPerPause;
    private int historyBufferSize;
    private int audioRecorderBufferSize;
    private int minBufferSize;
    private int addedLen;

    private final CircularArray historyBuffer;

    private AtomicBoolean loopStopped = new AtomicBoolean(false);
    private Handler delayhandler = new Handler();
    private ExecutorService threadExecutor = Executors.newSingleThreadExecutor();
    private int decoderState = STATE_INITIALIZED;

    private long readTimestamp;

    private CRC crc;

    /*package private*/SoniTalkDecoder(SoniTalkContext soniTalkContext, int sampleRate, SoniTalkConfig config) {
        this(soniTalkContext, sampleRate, config, 8, 50, false);
    }

    /*package private*/SoniTalkDecoder(SoniTalkContext soniTalkContext, int sampleRate, SoniTalkConfig config, int stepFactor, int frequencyOffsetForSpectrogram, boolean silentMode) {
        this(soniTalkContext, sampleRate, config, stepFactor, frequencyOffsetForSpectrogram, silentMode, 8, 2.0, 2.0);
    }

    /*package private*/SoniTalkDecoder(SoniTalkContext soniTalkContext, int sampleRate, SoniTalkConfig config, int stepFactor, int frequencyOffsetForSpectrogram, boolean silentMode, int bandPassFilterOrder, double startFactor, double endFactor) {
        this.soniTalkContext = soniTalkContext;
//TODO: check if f0 is higher than frequency offset.
        this.Fs = sampleRate;
        this.config = config;
        this.crc = new CRC();

        int f0 = config.getFrequencyZero();
        if ((f0*2) > Fs) {
            throw new IllegalArgumentException("Sample rate cannot be lower than two times the frequency zero. Please try a sample rate of 44100Hz and f0 under 22050Hz");
        }
        int bitperiod = config.getBitperiod();
        int pauseperiod = config.getPauseperiod();
        int nMessageBlocks = config.getMaxBytes();
        int nFrequencies = config.getnFrequencies();
        int frequencySpace = config.getFrequencySpace();

        this.silentMode = silentMode;
        this.frequencyOffsetForSpectrogram = frequencyOffsetForSpectrogram;
        this.stepFactor = stepFactor;
        this.bandPassFilterOrder = bandPassFilterOrder;
        this.startFactor = startFactor;
        this.endFactor = endFactor;


        //Log.d("AllSettings", "silentmode: " + String.valueOf(silentMode) + " f0: " + f0 + " bitperiod: " + bitperiod + " pauseperiod: " + pauseperiod + " maxChar: " + nMaxCharacters + " nFreq: " + nFrequencies + " freqSpacing: " + frequencySpace + " freqOffSpec: " + frequencyOffsetForSpectrogram + " stepFactor: " + stepFactor);
        /*Log.d("AllSettings", "silentmode: " + String.valueOf(this.silentMode));
        Log.d("AllSettings", "f0: " + f0);
        Log.d("AllSettings", "bitperiod: " + bitperiod);
        Log.d("AllSettings", "pauseperiod: " + pauseperiod);
        Log.d("AllSettings", "maxChar: " + nMaxCharacters);
        Log.d("AllSettings", "nFreq: " + nFrequencies);
        Log.d("AllSettings", "freqSpacing: " + frequencySpace);
        Log.d("AllSettings", "freqOffSpec: " + this.frequencyOffsetForSpectrogram);
        Log.d("AllSettings", "stepFactor: " + this.stepFactor);
        */

        bandpassWidth = frequencySpace *(nFrequencies /2);

        winLenForSpectrogram = bitperiod;
        winLenForSpectrogramInSamples = Math.round(Fs * (float) winLenForSpectrogram/1000);
        if (winLenForSpectrogramInSamples % 2 != 0) {
            winLenForSpectrogramInSamples ++; // Make sure winLenForSpectrogramInSamples is even
        }
        //Log.d("AllSettings", "SpectLen in Samples: " + String.valueOf(winLenForSpectrogramInSamples));

        frequencies = new int[nFrequencies];
        for(int i = 0; i < nFrequencies; i++){
            frequencies[i] = f0 + frequencySpace *i;
        }
        this.nBlocks = (int)Math.ceil(nMessageBlocks*2)+2;
        this.bitperiodInSamples = (int)Math.round(bitperiod * (float)sampleRate/1000);
        this.pauseperiodInSamples = (int)Math.round(pauseperiod * (float)sampleRate/1000);

        //analysisWinLen = getMinWinLenDividableByStepFactor((int)Math.ceil(bitperiodInSamples), stepFactor);
        analysisWinLen = (int)Math.round((float) bitperiodInSamples / 2 );
        analysisWinStep = (int)Math.round((float) analysisWinLen/ this.stepFactor);

        nAnalysisWindowsPerBit =  Math.round((bitperiodInSamples+pauseperiodInSamples)/(float)analysisWinStep); //number of analysis windows of bit+pause
        nAnalysisWindowsPerPause =  Math.round(pauseperiodInSamples/(float)analysisWinStep) ; //number of analysis windows during a pause

        addedLen = analysisWinLen; // Needed for stepping analysis
        historyBufferSize = ((bitperiodInSamples*nBlocks+pauseperiodInSamples*(nBlocks-1)));
        //Log.d("HistoryBufferSize", historyBufferSize +"");
        //Log.d("nBlocks", nBlocks +"");
        historyBuffer = new CircularArray(historyBufferSize);
        //analysisWinBuffer = new float[analysisWinLen];
        //historyBuffer1D = new float[analysisWinLen*10];
        //Log.d(TAG, "analysiswinlen: " + this.analysisWinLen);
        //Log.d(TAG, "analysiswinstep: " + this.analysisWinStep);
        //Log.d(TAG, "historybuffer1d: " + this.historyBuffer1D.length);

        audioRecorder = getInitializedAudioRecorder();
        //Log.d(TAG, "Decoder default priority: " + String.valueOf(this.getPriority()));
        //this.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
        //Log.d(TAG, "Decoder now in background priority: " + String.valueOf(this.getPriority()));
    }

    /**
     * Checks the microphone permission and the data-over-sound permission before it
     * starts the audiorecording. Every chunk of audio data will be added to the
     * historyBuffer. While the loop is running and it is not stopped it records data.
     * As soon as the historyBuffer is full every, it will be analyzed every loop run.
     */
    private void startDecoding() {
        if (! soniTalkContext.checkMicrophonePermission()) {
            throw new SecurityException("Does not have android.permission.RECORD_AUDIO.");
        }
        if ( ! soniTalkContext.checkSelfPermission(requestCode)) {
            // Make a SoniTalkException out of this ? (currently send a callback to the developer)
            Log.w(TAG, "SoniTalkDecoder requires a permission from SoniTalkContext.");
            return;//throw new SecurityException("SoniTalkDecoder requires a permission from SoniTalkContext. Use SoniTalkContext.checkSelfPermission() to make sure that you have the right permission.");
        }
        soniTalkContext.showNotificationReceiving();

        int readBytes = 0;
        int neededBytes = analysisWinStep;
        int counter = 1;
        int analysisCounter = 0;

        short tempBuffer[] = new short[neededBytes];
        float currentData[] = new float[neededBytes];

        // If the audio recorder couldn't be initialized
        if (audioRecorder == null) {
            // Generate a new Audio Decoder
            audioRecorder = getInitializedAudioRecorder();
        }

        try {
            audioRecorder.startRecording();

            // Wait until the audio recorder records ...
            if (audioRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    Log.e("AudiorecorderState", "Not recording, calling thread.sleep");
                    Thread.sleep(10);
                    if (audioRecorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        notifyMessageListenersOfError("The microphone is not available.");
                        if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                            audioRecorder.stop();
                        }
                        audioRecorder.release(); //release the recorder resources
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();  // set interrupt flag
                    notifyMessageListenersOfError("Audio error, could not start recording.");
                    if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                        audioRecorder.stop();
                    }
                    audioRecorder.release(); //release the recorder resources
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start recording. Error: " + e.getMessage());
            notifyMessageListenersOfError("Audio error, could not start recording.");
            if (audioRecorder.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecorder.stop();
            }
            audioRecorder.release(); //release the recorder resources
            return;
        }

        setDecoderState(STATE_LISTENING);

        decoderThread = new DecoderThread(config, (double)Fs, loopStopped, this);
        new Thread(decoderThread).start();

        while (!isLoopStopped()) {
            //for(int audioIndex = 0; audioIndex < stepFactor && !loopStopped; audioIndex++) { // NOTE: This for loop was used to know when a full winLen had been read, currently not used.
            // ACTUAL AUDIO READ
            readBytes = audioRecorder.read(tempBuffer, 0, neededBytes);

            if(readBytes > 0) {
                decoderThread.addSample(Arrays.copyOf(tempBuffer, readBytes));
            }

        } // THREAD-LOOP ENDS HERE

        setDecoderState(STATE_STOPPED);

        if (audioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecorder.stop();
        }

        audioRecorder.release();
        audioRecorder = null;
        //analysisHistoryFillingBuffer = 0;
        //Log.d(TAG, "Message Decoder Thread stopped.");
    }


    /**
     *
     * Converts an input array from [-1.0;1.0] float to short full range and returns it
     * @param input
     * @return a short array containing short values with a distribution similar to the input one
     */
    private static short [] convertFloatToShort(float[] input) {
        short[] output = new short[input.length];
        for (int i = 0; i < input.length; i++) {
            // Do we actually need float anywhere ? Switch to double ?
            output[i] = (short) (input[i] * Short.MAX_VALUE);
        }
        return output;
    }

    private AudioRecord getInitializedAudioRecorder() {
        minBufferSize = AudioRecord.getMinBufferSize(Fs,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if(minBufferSize < 0) {
            Log.e(TAG, "Error getting the minimal buffer size: " + minBufferSize);
            return null;
        }

        try {

            audioRecorderBufferSize = analysisWinLen*10; // Empirically decided

            // Initialize the buffer size such that at least the minimum size is buffered
            if (audioRecorderBufferSize < minBufferSize) {
                audioRecorderBufferSize = minBufferSize;
                //Log.d(TAG, "Minimum buffersize will be used: " + audioRecorderBufferSize);
            }
            /*else {
                audioRecorderBufferSize = audioRecorderBufferSize; // * 2;
                //Log.d(TAG, "buffersize-else: " + audioRecorderBufferSize);
            }*/

            //audioRecorderBufferSize = audioRecorderBufferSize*10;
            audioRecorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    Fs, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, audioRecorderBufferSize);

            if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Could not open the audio recorder, initialization failed !");
                return null;
            }
        } catch(IllegalArgumentException e) {
            //e.printStackTrace();
            Log.e(TAG, "Audio Recorder was not initialized because of an IllegalArgumentException. Error message: " + e.getMessage());
            return null;
        }

        return audioRecorder;
    }

    /**
     * Captures audio and tries detecting/decoding messages. Audio processing occurs in a separate Thread.
     * Detected messages will be notified to listeners via the onMessageReceived callback.
     * It is possible to cancel using stopReceiving().
     * @throws DecoderStateException
     */
    public void receiveBackground(int requestCode) throws DecoderStateException {
        this.requestCode = requestCode;
        // Do we really want to be able to restart a stopped Decoder ?
        if (getDecoderState() == STATE_INITIALIZED || getDecoderState() == STATE_STOPPED) {
            threadExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    startDecoding();
                }
            });
        }
        else if (getDecoderState() == STATE_CANCELLED) {
            throw new DecoderStateException("Cannot start a Decoder after it was cancelled.");
        }
        else if (getDecoderState() == STATE_LISTENING) {
            throw new DecoderStateException("Cannot start a Decoder already listening.");
        }
        else {
            throw new DecoderStateException("Cannot start the Decoder, unexpected state.");
        }
    }

    /**
     * Stops the capturing and decoding process. This must be called at the latest in your app
     * onStop() or in your Service onDestroy() to release resources. Please do not keep the microphone
     * access when you do not need it anymore.
     */
    public void stopReceiving() {
        //Log.d(TAG, "Stop receiving.");
        setLoopStopped(true);

        soniTalkContext.cancelNotificationReceiving();

        delayhandler.removeCallbacksAndMessages(null); // Consider doing it more fine grained
        List<Runnable> cancelledRunnables = threadExecutor.shutdownNow();
        if (!cancelledRunnables.isEmpty())
            Log.d(TAG, "Cancelled " + cancelledRunnables.size() + " tasks.");
    }

    /**
     * Pauses the current capturing/decoding process without cancelling the potential timers.
     */
    public void pause() {
        //Log.d(TAG, "Pause receiving.");
        setLoopStopped(true);
        soniTalkContext.cancelNotificationReceiving();
    }

    /**
     * Resumes audio capturing and detecting/decoding messages. Audio processing occurs in a separate Thread.
     * Detected messages will be notified to listeners via the onMessageReceived callback.
     * It is possible to cancel using stopReceiving().
     * @throws DecoderStateException
     */
    public void resume() throws DecoderStateException {
        //Log.d(TAG, "Resume receiving.");
        receiveBackground(this.requestCode);
    }


    private synchronized boolean isLoopStopped() {
        return loopStopped.get();
    }

    private synchronized void setLoopStopped(boolean loopStopped) {// Consider using an internal object for the synchronization
        this.loopStopped.set(loopStopped);
    }

    public void addMessageListener(MessageListener listener) {
        this.messageListeners.add(listener);
    }

    public boolean removeMessageListener(MessageListener listener) {
        return this.messageListeners.remove(listener);
    }

    private void notifyMessageListeners(SoniTalkMessage decodedMessage) {
        for(MessageListener listener: messageListeners) {
            listener.onMessageReceived(decodedMessage);
        }
    }

    private void notifyMessageListenersOfError(String errorMessage) {
        for(MessageListener listener: messageListeners) {
            listener.onDecoderError(errorMessage);
        }
    }

    public void addSpectrumListener(SpectrumListener listener) {
        this.spectrumListeners.add(listener);
    }

    public boolean removeSpectrumListener(SpectrumListener listener) {
        return this.spectrumListeners.remove(listener);
    }

    private void notifySpectrumListeners(float[][] spectrum, boolean crcIsCorrect) {
        for(SpectrumListener listener: spectrumListeners) {
            listener.onSpectrum(spectrum, crcIsCorrect);
        }
    }

    @DecoderState
    private synchronized int getDecoderState() {
        return decoderState;
    }

    private synchronized void setDecoderState(@DecoderState int state) {
        this.decoderState = state;
    }

    /**
     * Returns true if detected messages will be returned with the original audio.
     * @return true if detected messages will be returned with the original audio
     */
    public synchronized boolean returnsRawAudio() {
        return returnRawAudio;
    }

    /**
     * Decides if detected messages will be returned with the original audio or not. Useful for
     * debugging or replaying a message.
     * @param returnRawAudio
     */
    public synchronized void setReturnRawAudio(boolean returnRawAudio) {
        this.returnRawAudio = returnRawAudio;
    }



    public static final class DecoderThread implements Runnable {
        private Queue<short[]> bufferToProcess = new ConcurrentLinkedQueue<short[]>();
        private OpenWarble openWarble;
        private AtomicBoolean loopStopped;

        public DecoderThread(SoniTalkConfig config, Double Fs, AtomicBoolean loopStopped, MessageCallback callback) {
            Configuration configuration = config.getDriverConfiguration(Fs);
            openWarble = new OpenWarble(configuration);
            openWarble.setCallback(callback);
            this.loopStopped = loopStopped;
        }

        public void addSample(short[] sample) {
            bufferToProcess.add(sample);
        }

        @Override
        public void run() {
            while (!loopStopped.get()){
                while(!bufferToProcess.isEmpty()) {
                    short[] buffer = bufferToProcess.poll();
                    if(buffer != null) {
                        boolean doProcessBuffer = true;
                        while(doProcessBuffer) {
                            doProcessBuffer = false;
                            double[] samples = new double[Math.min(buffer.length, openWarble.getMaxPushSamplesLength())];
                            for (int i = 0; i < samples.length; i++) {
                                samples[i] = buffer[i] / (double)Short.MAX_VALUE;
                            }
                            openWarble.pushSamples(samples);
                            if (buffer.length > samples.length) {
                                buffer = Arrays.copyOfRange(buffer, samples.length, buffer.length);
                                doProcessBuffer = true;
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }
    }
}

