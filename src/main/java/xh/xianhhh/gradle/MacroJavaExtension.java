package xh.xianhhh.gradle;

import xh.xianhhh.macro.ConstExprMode;

/** User-facing Gradle configuration for MacroJava. */
public class MacroJavaExtension {
    private boolean enabled = true;
    private int maxArrayElements = 8_388_608;
    private long stepsPerArrayElement = 24L;
    private long maxStepLimit = 300_000_000L;
    private long baseStepLimit = 100_000_000L;
    private boolean shareTemplateArray;
    private boolean checkLegit = true;
    private boolean preserveReflectiveMembers;
    private ConstExprMode mode = ConstExprMode.SMART;
    private int maxCallDepth = 4096;
    private int workers;
    private boolean showProgress;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxArrayElements() {
        return maxArrayElements;
    }

    public void setMaxArrayElements(int maxArrayElements) {
        if (maxArrayElements < 1) throw new IllegalArgumentException("maxArrayElements must be greater than 0");
        this.maxArrayElements = maxArrayElements;
    }

    public long getStepsPerArrayElement() {
        return stepsPerArrayElement;
    }

    public void setStepsPerArrayElement(long stepsPerArrayElement) {
        if (stepsPerArrayElement < 0) throw new IllegalArgumentException("stepsPerArrayElement must not be negative");
        this.stepsPerArrayElement = stepsPerArrayElement;
    }

    public long getMaxStepLimit() {
        return maxStepLimit;
    }

    public long getBaseStepLimit() { return baseStepLimit; }
    public void setBaseStepLimit(long baseStepLimit) {
        if (baseStepLimit < 1L) throw new IllegalArgumentException("baseStepLimit must be greater than 0");
        this.baseStepLimit = baseStepLimit;
    }

    public void setMaxStepLimit(long maxStepLimit) {
        if (maxStepLimit < 1_000_000L) throw new IllegalArgumentException("maxStepLimit must be at least 1000000");
        this.maxStepLimit = maxStepLimit;
    }

    public boolean isShareTemplateArray() {
        return shareTemplateArray;
    }

    public void setShareTemplateArray(boolean shareTemplateArray) {
        this.shareTemplateArray = shareTemplateArray;
    }

    public boolean isCheckLegit() {
        return checkLegit;
    }

    public void setCheckLegit(boolean checkLegit) {
        this.checkLegit = checkLegit;
    }

    public boolean isPreserveReflectiveMembers() { return preserveReflectiveMembers; }
    public void setPreserveReflectiveMembers(boolean preserveReflectiveMembers) {
        this.preserveReflectiveMembers = preserveReflectiveMembers;
    }

    public ConstExprMode getMode() { return mode; }
    public void setMode(ConstExprMode mode) {
        if (mode == null) throw new IllegalArgumentException("mode must not be null");
        this.mode = mode;
    }

    public int getMaxCallDepth() { return maxCallDepth; }
    public void setMaxCallDepth(int maxCallDepth) {
        if (maxCallDepth < 1) throw new IllegalArgumentException("maxCallDepth must be greater than 0");
        this.maxCallDepth = maxCallDepth;
    }

    public int getWorkers() { return workers; }
    public void setWorkers(int workers) {
        if (workers < 0 || workers > 256) throw new IllegalArgumentException("workers must be between 0 and 256");
        this.workers = workers;
    }

    public boolean isShowProgress() { return showProgress; }
    public void setShowProgress(boolean showProgress) { this.showProgress = showProgress; }
}
