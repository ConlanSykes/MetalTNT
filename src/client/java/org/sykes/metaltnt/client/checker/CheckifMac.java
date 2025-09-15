package org.sykes.metaltnt.client.checker;

public class CheckifMac {
       public static boolean isMac() {
           return System.getProperty("os.name").toLowerCase().contains("mac");
       }
}
