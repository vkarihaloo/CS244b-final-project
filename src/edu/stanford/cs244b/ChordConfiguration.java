package edu.stanford.cs244b;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.hibernate.validator.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.dropwizard.Configuration;

public class ChordConfiguration extends Configuration {
    @Valid
    @NotNull
    private Chord chord = new Chord();
    
    public Chord getChord() {
        return chord;
    }

    public class Chord {
        /** Host which should be queried when joining the chord ring */
        @NotNull
        @JsonProperty
        private InetAddress entryHost;
        
        /** Port which should be queried when joining the chord ring */
        @Min(1)
        @Max(65535)
        @JsonProperty
        private int entryPort;

        public InetAddress getEntryHost() {
            return entryHost;
        }
        
        public void setEntryPoint(String entryHost) throws UnknownHostException {
            this.entryHost = InetAddress.getByName(entryHost);
        }
        
        public int getEntryPort() {
            return entryPort;
        }

        public void setEntryPort(int entryPort) {
            this.entryPort = entryPort;
        }    
    }
}