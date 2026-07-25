package exposed.thunder.thunderLBs.font;

public record PadDiagnostic(Severity severity, String message) {
    public enum Severity {

        ERROR,

        WARNING
    }

    static PadDiagnostic error(String message) {
        return new PadDiagnostic(Severity.ERROR, message);
    }

    static PadDiagnostic warning(String message) {
        return new PadDiagnostic(Severity.WARNING, message);
    }

    @Override
    public String toString() {
        return message;
    }
}
