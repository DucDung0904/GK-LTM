package udpgk.server;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** One exchange-rate snapshot exchanged through UDP. */
public record RateRecord(String time, double tokyo, double paris, double seoul) {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Locale DATA_LOCALE = Locale.US;

    public static RateRecord randomSnapshot() {
        return new RateRecord(
                LocalTime.now().format(TIME_FORMAT),
                randomRate(100.0, 160.0),
                randomRate(90.0, 130.0),
                randomRate(950.0, 1_200.0));
    }

    private static double randomRate(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    public String serialize() {
        return String.format(DATA_LOCALE, "%s;%.2f;%.2f;%.2f", time, tokyo, paris, seoul);
    }

    public static RateRecord parse(String message) {
        String[] values = message.trim().split(";", -1);
        if (values.length != 4) {
            throw new IllegalArgumentException("Invalid exchange-rate packet: " + message);
        }
        return new RateRecord(
                values[0],
                Double.parseDouble(values[1]),
                Double.parseDouble(values[2]),
                Double.parseDouble(values[3]));
    }
}
