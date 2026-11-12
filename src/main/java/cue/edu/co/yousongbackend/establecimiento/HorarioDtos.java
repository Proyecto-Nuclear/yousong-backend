package cue.edu.co.yousongbackend.establecimiento;

/** Bodies of the weekly schedule API. Times are wall-clock {@code HH:mm} in the establishment's time zone. */
public final class HorarioDtos {

    private HorarioDtos() {
    }

    /**
     * One window as the owner sends it: {@code diaSemana} 1 (Monday) to 7 (Sunday). Fields are read as raw values
     * and validated by the service, so every malformed window answers the same 400 INVALID_SCHEDULE.
     */
    public record VentanaRequest(Integer diaSemana, String inicio, String fin) {
    }

    /** One window as stored; {@code cruzaMedianoche} is derived ({@code fin <= inicio} ends the next day). */
    public record VentanaResponse(int diaSemana, String inicio, String fin, boolean cruzaMedianoche) {
    }
}
