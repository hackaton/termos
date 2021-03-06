package no.hackaton.termos;

import static java.lang.Character.*;
import static java.lang.Character.valueOf;
import static java.lang.Integer.*;
import static no.hackaton.termos.NoCompleter.*;

import java.io.*;
import java.util.*;

/**
 * TODO: Look into CharsetDecoder to decode the data on the fly and echo every successfully read byte back to the client.
 * <p/>
 * TODO: Support history
 * TODO: Support tab-completion
 *
 * @author <a href="mailto:trygvis@java.no">Trygve Laugst&oslash;l</a>
 * @version $Id$
 */
@SuppressWarnings({"OctalInteger"})
public class ReadLine {
    public static final byte ETX = 003; // Ctrl-c
    public static final byte BS = 010;
    public static final byte TAB = 011;
    public static final byte FF = 014;
    public static final byte ESC = 033;
    public static final byte SPACE = 040;
    public static final byte BEL = 0007;

    public static final byte[] clearEol = {ESC, '[', 'K'};
    public static final byte[] cursorHome = {ESC, '[', 'H'};
    public static final byte[] cursorUp = {ESC, '[', 'A'};
    public static final byte[] cursorDn = {ESC, '[', 'B'};
    public static final byte[] cursorRt = {ESC, '[', 'C'};
    public static final byte[] cursorLf = {ESC, '[', 'D'};
    public static final byte[] clearScreenEd2 = {ESC, '[', '2', 'J'};

    private final InputStream inputStream;
    private final OutputStream outputStream;

    List<Character> chars = new ArrayList<Character>();
    int position = 0;
    private byte erase;

    public ReadLine(InputStream inputStream, OutputStream outputStream, ReadLineEnvironment environment) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;

        Integer erase = environment.erase;
        this.erase = erase == null ? 0x7f : erase.byteValue();
    }

    public int getPosition() {
        return position;
    }

    public String readLine() {
        return readLine("", noCompleter);
    }

    public String readLine(String prompt) {
        return readLine(prompt, noCompleter);
    }

    public String readLine(String prompt, Completer completer) {
        try {
            return doRead(prompt, completer);
        } catch (IOException e) {
            return null;
        }
    }

    private String doRead(String prompt, Completer completer) throws IOException {
        int b;
        int tabCount = 0;

        print(prompt);
        flush();

        // TODO: This should read all available bytes

        do {
            b = tryNext();

            if (b == -1) {
                break;
            }

            if (0x20 <= b && b < 0x7f) {
                outputStream.write(b);
                flush();

                if (isEndOfLine()) {
                    chars.add((char) b);
                    position++;
                } else {
                    chars.set(position++, (char) (0xff & b));
                }
            }

            if (b == '\r') {
                // This seems to be correct, at least if ONLCR=1
                outputStream.write('\r');
                outputStream.write('\n');
                flush();
                break;
            }

            if (b == erase) {
                if (position > 0) {
                    position--;
                    chars.remove(position);
                    outputStream.write(BS);
                    outputStream.write(clearEol);
                }
            } else if (b == FF) {
                outputStream.write(cursorHome);
                outputStream.write(clearScreenEd2);
                print(prompt);
                for (Character c : chars) {
                    // TODO: Encode
                    outputStream.write((byte) c.charValue());
                }
            } else if (b == ESC) {
                b = requireNext();

                if (b == '[') {
                    b = requireNext();
                    if (b == 'A' || b == 'B') { // Up || down
                        // ignore
                    } else if (b == 'C') { // Right
                        if (isEndOfLine()) {
                            position++;
                            outputStream.write(cursorRt);
                        }
                    } else if (b == 'D') { // Left
                        if (position > 0) {
                            position--;
                            outputStream.write(cursorLf);
                        }
                    }
                } else if (b == 'b') { // backward-word
                    int i = findStartOfWord(chars, position);

                    int count = position - i;
                    for (int x = 0; x < count; x++) {
                        outputStream.write(cursorLf);
                    }
                    position = position - i;
                } else if (b == 'f') { // forward-word
                    int i = findEndOfWord(chars, position);

                    int count = position - i;
                    for (int x = 0; x < count; x++) {
                        outputStream.write(cursorLf);
                    }
                    position = position - i;
                }
            } else if (b == TAB) {
                // -----------------------------------------------------------------------
                // Tab completion
                // -----------------------------------------------------------------------

                // -----------------------------------------------------------------------
                // TODO: Right now it requires TAB to be hit twice before completion is
                // shown. However, it should only require a single tab to complete any
                // matches as far as possible.
                // -----------------------------------------------------------------------

                String currentLine = charsToString();

                // TODO: This probably assumes too much about how a program wants to completeStrings something.
                // It's probably best to pass all the parameters to the completer and have it return a bigger object.
                // In: current line, cursor position, out: options

                int wordStart = currentLine.lastIndexOf(' ', position);
                if(wordStart == -1) {
                    wordStart = 0;
                }
                else {
                    wordStart += 1;
                }

                System.out.println("ReadLine.doRead");
                System.out.println("currentLine = '" + currentLine + "', wordStart=" + wordStart + ", position=" + position);
                List<String> options = completer.complete(currentLine, position);
                System.out.println("options:");
                System.out.println(options);

                String s = findLongestMatch(0, options);
                System.out.println("Longest match = " + s + ", (position - wordStart)=" + (position - wordStart));

/*
foo bar
    ^       word start
      ^     position

completion="barbara"
 */
                int wordPosition = position - wordStart;

                for(int i = wordPosition; i < s.length(); i++) {
                    char c = s.charAt(i);
                    chars.add(c);
                    outputStream.write(c);
                }

                if (options.size() == 0) {
                    // Do nothing
                }
                else if (options.size() == 1) {
                    tabCount = 0;
                    String match = options.get(0);
                    int length = match.length();

                    // TODO: This should probably *insert* characters instead of *adding* as completion might
                    // happen in the middle of a string
//                    for (int i = currentLine.length(); i < length; i++) {
//                        char c = match.charAt(i);
//                        chars.add(c);
//                        outputStream.write(c);
//                    }
                    // Add an extra space after the match to be ready to write arguments to the command
                    chars.add(' ');
                    outputStream.write(' ');
                } else {

//                    for (int i = currentLine.length(); i < s.length(); i++) {
//                        char c = s.charAt(i);
//                        chars.add(c);
//                        outputStream.write(c);
//                    }

                    System.out.println("s='" + s + "', currentLine.length()=" + currentLine.length() + ", tabCount = " + tabCount);
                    // If we're at the longest common sub string, require tabCount to be 2 to write out all the hits
                    if (chars.size() == position) {
                        if (tabCount == 1) {
                            outputStream.write('\r');
                            outputStream.write('\n');
                            TreeSet<String> sortedOptions = new TreeSet<String>(options);

                            // TODO: Format the options in a prettier way
                            for (String option : sortedOptions) {
                                println(option);
                            }
                            print(prompt + charsToString());
                            tabCount = 0;
                        } else {
                            tabCount++;
                        }
                    }
                }
                position = positionAtToEndOfLine();
            } else if (b == ETX) {
                tabCount = 0;
                position = 0;
                chars = new ArrayList<Character>();
                println("");
                print(prompt);
            }
            flush();
        } while (true);

        // TODO: Decode
        return charsToString();
    }

    /**
     *
     * @param start The character to start checking at
     * @param options The list of strings to search in
     */
    public static String findLongestMatch(int start, List<String> options) {
        String s = options.get(0);

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);

            for (int j = 1, optionsSize = options.size(); j < optionsSize; j++) {
                String t = options.get(j);

                if (t.length() == i) {
                    return t;
                }

                if (c != t.charAt(i)) {
                    return t.substring(0, i);
                }
            }
        }

        return s;
    }

    private int positionAtToEndOfLine() {
        return chars.size();
    }

    private String charsToString() {
        StringBuffer buffer = new StringBuffer(chars.size());
        for (Character c : chars) {
            buffer.append(valueOf(c));
        }
        return buffer.toString();
    }

    private boolean isEndOfLine() {
        return position == chars.size();
    }

    private int tryNext() throws IOException {
        int b = inputStream.read();
        if (b == -1) {
            return -1;
        }
        System.out.println("b = 0x" + (b < 16 ? "0" : "") + toHexString(b));
        return b;
    }

    private int requireNext() throws IOException {
        int b = inputStream.read();
        if (b == -1) {
            throw new IOException("Unexpected EOF.");
        }
        System.out.println("b = 0x" + (b < 16 ? "0" : "") + toHexString(b));
        return b;
    }

    public static int findStartOfWord(List<Character> chars, int position) {
//        int i = position;
        return 0;
    }

    public static int findEndOfWord(List<Character> chars, int position) {
        // Find first word
        int i = findStartOfWord(chars, position);
        int end = chars.size();

        for (; i < end; i++) {
            Character c = chars.get(i);
            if (!isLetter(c) && !isDigit(c)) {
                return i;
            }
        }

        return i;
    }

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------

    private byte[] toBytes(String s) {
        // TODO: Encode
        return s.getBytes();
    }

    public void print(String s) throws IOException {
        outputStream.write(toBytes(s));
    }

    public void println(String s) throws IOException {
        outputStream.write(toBytes(s));
        outputStream.write('\r');
        outputStream.write('\n');
    }

    /**
     * Sets the prompt of the terminal.
     */
    public void sendPrompt(String s) throws IOException {
        outputStream.write(ESC);
        outputStream.write(toBytes("]0;")); // TODO: This should be a byte array
        outputStream.write(toBytes(s));
        outputStream.write(BEL);
        flush();
    }

    public void flush() throws IOException {
        outputStream.flush();
    }
}
