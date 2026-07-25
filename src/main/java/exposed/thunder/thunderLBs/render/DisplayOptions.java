package exposed.thunder.thunderLBs.render;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

public final class DisplayOptions {
    private Display.Billboard billboard = Display.Billboard.FIXED;
    private boolean shadowed = false;
    private boolean seeThrough = false;
    private int lineWidth = 200;
    private TextAlignment alignment = TextAlignment.CENTER;
    private int interpolationDuration = 1;
    private int teleportDuration = 1;
    private byte opacity = (byte) 0;
    private Component text = Component.empty();
    private Vector3f translation = new Vector3f();
    private Vector3f scale = new Vector3f(1.0F, 1.0F, 1.0F);
    private float viewRange = 1.0F;
    private Player viewer;

    public DisplayOptions billboard(Display.Billboard billboard) {
        this.billboard = billboard;
        return this;
    }

    public DisplayOptions shadowed(boolean shadowed) {
        this.shadowed = shadowed;
        return this;
    }

    public DisplayOptions seeThrough(boolean seeThrough) {
        this.seeThrough = seeThrough;
        return this;
    }

    public DisplayOptions lineWidth(int lineWidth) {
        this.lineWidth = lineWidth;
        return this;
    }

    public DisplayOptions alignment(TextAlignment alignment) {
        this.alignment = alignment == null ? TextAlignment.CENTER : alignment;
        return this;
    }

    public DisplayOptions interpolationDuration(int ticks) {
        this.interpolationDuration = ticks;
        return this;
    }

    public DisplayOptions teleportDuration(int ticks) {
        this.teleportDuration = ticks;
        return this;
    }

    public DisplayOptions opacity(byte opacity) {
        this.opacity = opacity;
        return this;
    }

    public DisplayOptions text(Component text) {
        this.text = text;
        return this;
    }

    public DisplayOptions translation(Vector3f translation) {
        this.translation.set(translation);
        return this;
    }

    public DisplayOptions translation(float x, float y, float z) {
        this.translation.set(x, y, z);
        return this;
    }

    public DisplayOptions scale(Vector3f scale) {
        this.scale.set(scale);
        return this;
    }

    public DisplayOptions scale(float x, float y, float z) {
        this.scale.set(x, y, z);
        return this;
    }

    public DisplayOptions viewRange(float viewRange) {
        this.viewRange = viewRange;
        return this;
    }

    public DisplayOptions viewer(Player viewer) {
        this.viewer = viewer;
        return this;
    }

    public Display.Billboard billboard() {
        return billboard;
    }

    public boolean shadowed() {
        return shadowed;
    }

    public boolean seeThrough() {
        return seeThrough;
    }

    public int lineWidth() {
        return lineWidth;
    }

    public TextAlignment alignment() {
        return alignment;
    }

    public int interpolationDuration() {
        return interpolationDuration;
    }

    public int teleportDuration() {
        return teleportDuration;
    }

    public byte opacity() {
        return opacity;
    }

    public Component text() {
        return text;
    }

    public Vector3f translation() {
        return translation;
    }

    public Vector3f scale() {
        return scale;
    }

    public float viewRange() {
        return viewRange;
    }

    public Player viewer() {
        return viewer;
    }
}
