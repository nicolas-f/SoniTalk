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

import android.content.res.Configuration;

/**
 * Configuration, or profile, used to transmit data. The emitter and receiver of a message must use
 * the same configuration. A crucial use case will be transmitting with several profiles simultaneously.
 * This will allow for faster communication within one app and simultaneous communication of several apps.
 * To getDriverConfiguration preset configurations, the utility class ConfigFactory has the function loadFromJson().
 */
public class SoniTalkConfig {
    private int frequencyZero;// = 18000; (Hz)
    private int bitperiod;// = 100; (ms)
    private int pauseperiod;// = 0; (ms)
    private int maxBytes;
    private int nFrequencies;// = 16;
    private int frequencySpace;// = 100; (Hz)

    public SoniTalkConfig(int frequencyZero, int bitperiod, int pauseperiod, int maxBytes, int nFrequencies, int frequencySpace) {
        this.frequencyZero = frequencyZero;
        this.bitperiod = bitperiod;
        this.pauseperiod = pauseperiod;
        this.maxBytes = maxBytes;
        this.nFrequencies = nFrequencies;
        this.frequencySpace = frequencySpace;
    }

    public org.noise_planet.jwarble.Configuration getDriverConfiguration(double sampleRate) {
        return new org.noise_planet.jwarble.Configuration(getMaxBytes(), sampleRate,
                getFrequencyZero(), getFrequencySpace(), 0,
                getBitperiod() / 1000.0,
                getPauseperiod() / 1000.0, org.noise_planet.jwarble.Configuration.DEFAULT_TRIGGER_SNR,
                org.noise_planet.jwarble.Configuration.DEFAULT_DOOR_PEAK_RATIO, org.noise_planet.jwarble.Configuration.DEFAULT_RS_ENCODE);
        //return org.noise_planet.jwarble.Configuration.getAudible(maxBytes, sampleRate);
    }

    public int getFrequencyZero() {
        return frequencyZero;
    }

    public void setFrequencyZero(int frequencyZero) {
        this.frequencyZero = frequencyZero;
    }

    public int getBitperiod() {
        return bitperiod;
    }

    public void setBitperiod(int bitperiod) {
        this.bitperiod = bitperiod;
    }

    public int getPauseperiod() {
        return pauseperiod;
    }

    public void setPauseperiod(int pauseperiod) {
        this.pauseperiod = pauseperiod;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getnFrequencies() {
        return nFrequencies;
    }

    public void setnFrequencies(int nFrequencies) {
        this.nFrequencies = nFrequencies;
    }

    public int getFrequencySpace() {
        return frequencySpace;
    }

    public void setFrequencySpace(int frequencySpace) {
        this.frequencySpace = frequencySpace;
    }
}
