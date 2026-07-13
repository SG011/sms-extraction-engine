package com.sms.extraction.domain;

public final class RegexVariant {

    private final String regex;
    private final int group;

    public RegexVariant(String regex) {
        this.regex = regex;
        this.group = 0;
    }

    public RegexVariant(String regex, int group) {
        this.regex = regex;
        this.group = group;
    }

    public String getRegex() { return regex; }
    public int getGroup()    { return group; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegexVariant other)) return false;
        return group == other.group && java.util.Objects.equals(regex, other.regex);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(regex, group);
    }

    @Override
    public String toString() {
        return "RegexVariant{regex='" + regex + "', group=" + group + "}";
    }
}
