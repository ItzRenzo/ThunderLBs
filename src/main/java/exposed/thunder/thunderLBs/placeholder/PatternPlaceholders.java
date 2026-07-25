package exposed.thunder.thunderLBs.placeholder;

@FunctionalInterface
public interface PatternPlaceholders {

    PatternPlaceholders NONE = pattern -> pattern;


    String resolve(String pattern);
}
