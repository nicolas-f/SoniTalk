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

import org.noise_planet.jwarble.Configuration;
import org.noise_planet.jwarble.OpenWarble;

import java.util.Arrays;

import at.ac.fhstp.sonitalk.utils.SignalGenerator;


/**
 * Encodes the forwarded byte array and uses a SignalGenerator to getDriverConfiguration the raw
 * audio data. This audio data is then concatenated to have the right
 * shape for creating an audio track and sending it.
 */
public class SoniTalkEncoder {
    private final SoniTalkContext soniTalkContext;

    private int Fs;
    private SoniTalkConfig config;
    private SignalGenerator signalGen;

    private final Configuration configuration;


    /**
     * Default constructor using a 44100Hz sample rate (works on all devices)
     *
     * @param config
     */
    /*package private*/SoniTalkEncoder(SoniTalkContext soniTalkContext, SoniTalkConfig config) {
        this(soniTalkContext, 44100, config);
    }

    /*package private*/SoniTalkEncoder(SoniTalkContext soniTalkContext, int sampleRate, SoniTalkConfig config) {
        this.soniTalkContext = soniTalkContext;
        this.Fs = sampleRate;
        this.config = config;
        configuration = config.getDriverConfiguration(sampleRate);
        int f0 = config.getFrequencyZero();
        if ((f0 * 2) > Fs) {
            throw new IllegalArgumentException("Sample rate cannot be lower than two times the frequency zero. Please try a sample rate of 44100Hz and f0 under 22050Hz");
        }

        signalGen = new SignalGenerator(Fs, config);
    }

    /**
     * Encodes a byte array of data using the configuration specified in the constructor.
     * The SoniTalkMessage returned can then be send via a SoniTalkSender object.
     *
     * @param data to be encoded
     * @return a SoniTalkMessage containing the encoded data to be sent via a SoniTalkSender
     */
    public SoniTalkMessage generateMessage(byte[] data) {
        data = Arrays.copyOf(data, configuration.payloadSize);
        SoniTalkMessage message = new SoniTalkMessage(data);
        short[] generatedSignal = encode(data);
        message.setRawAudio(generatedSignal);
        return message;
    }


    /**
     * Takes a byte array and encodes it to a bit sequence. Adds CRC bit sequence for error checking.
     * Creates an inversed version of that bit sequence. Creates a short array with signal data depending
     * on the bit sequence and number of frequencies.
     *
     * @param data to be encoded
     * @return a short array with signal data
     */
    private short[] encode(byte[] data) {
        OpenWarble openWarble = new OpenWarble(configuration);
        double peak = Short.MAX_VALUE * (3.0 / 4.0);
        double[] signal = openWarble.generateSignal(peak, data);
        short[] shortSignal = new short[signal.length];
        for (int i = 0; i < shortSignal.length; i++) {
            shortSignal[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, signal[i]));
        }
        return shortSignal;
    }

}
