package com.naivework;

import java.util.*;

public class BasicInterpreter {
    public interface InputProvider { String input(String prompt); }
    public interface InputStateProvider extends InputProvider {
        int getKey();
        boolean isTouch();
        int getTouchX();
        int getTouchY();
        int getScreenX();
        int getScreenY();
        int getGiro();
        double giroX();
        double giroY();
    }
    public interface ScreenProvider extends InputStateProvider {
        void print(String s);
        void printAt(int row, int col, String s);
        void setScreen(int mode, int charset) throws Exception;
        void beep(double duration, double pitch) throws Exception;
        void play(String[] patterns, boolean wait) throws Exception;
        void setInk(int color);
        void setPaper(int color);
        void tab(int count);
        void plot(int x, int y, int color);
        void draw(int dx, int dy, int color) throws Exception;
        void drawLine(int x1, int y1, int x2, int y2) throws Exception;
        void drawLineTo(int x2, int y2) throws Exception;
        void circle(int xc, int yc, int rx, int ry, boolean filled) throws Exception;
        void triangle(int x1, int y1, int x2, int y2, int x3, int y3, boolean filled) throws Exception;
        void paintArea(int x, int y) throws Exception;
        int point(int x, int y) throws Exception;
        void clearScreen();
        void scrollView(int dx, int dy);
        void rollView(int dx, int dy);
        void flushDisplay();
        boolean makeMassive(int x, int y, String name) throws Exception;
        int openMassive(int x, int y, String name) throws Exception;
        int peekMassive(int x, int y) throws Exception;
        void pokeMassive(int x, int y, int value) throws Exception;
    }

    private final InputProvider input;
    private final Map<String, Double> numbers = new HashMap<String, Double>();
    private final Map<String, String> strings = new HashMap<String, String>();
    private final ArrayList<String> dataValues = new ArrayList<String>();
    // During one RUN, MAKE may operate on only one distinct NBM file.
    // The same file may be created/recreated repeatedly.
    private final HashSet<String> makeNamesThisRun = new HashSet<String>();
    private int dataPointer = 0;
    private int inkColor = 255;
    private int paperColor = 0;


    private final Map<String, double[]> numArrays = new HashMap<String, double[]>();
    private final Map<String, int[]> numArrayDims = new HashMap<String, int[]>();
    private final Map<String, String[]> strArrays = new HashMap<String, String[]>();
    private final Map<String, int[]> strArrayDims = new HashMap<String, int[]>();
    private final Map<String, UserFunction> userFunctions = new HashMap<String, UserFunction>();

    private final StringBuilder out = new StringBuilder();
    private final List<Line> program = new ArrayList<Line>();
    private final Map<Integer, Integer> labels = new HashMap<Integer, Integer>();
    private final Stack<ForState> loops = new Stack<ForState>();
    private final Stack<Integer> gosubStack = new Stack<Integer>();
    private final Random random = new Random();
    private volatile boolean stopRequested = false;
    private volatile int currentSourceLine = 0;
    private volatile int errorSourceLine = 0;

    public void requestStop() {
        stopRequested = true;
    }

    public int getCurrentSourceLine() { return currentSourceLine; }
    public int getErrorSourceLine() { return errorSourceLine; }

    public BasicInterpreter(InputProvider provider) { input = provider; }
    public BasicInterpreter() { this(null); }

    public String run(String source) {
        stopRequested = false;
        currentSourceLine = 0;
        errorSourceLine = 0;
        numbers.clear();
        dataValues.clear();
        dataPointer = 0; strings.clear(); out.setLength(0);
        program.clear(); labels.clear(); loops.clear(); gosubStack.clear(); userFunctions.clear();

        String[] raw = source.replace("\r", "").split("\n");
        for (int i = 0; i < raw.length; i++) {
            try {
                Line l = parseLine(raw[i], i + 1);
                if (l.text.length() > 0) {
                    if (l.number >= 0) {
                        if (labels.containsKey(Integer.valueOf(l.number)))
                            throw new Exception("Duplicate line number");
                        labels.put(Integer.valueOf(l.number), Integer.valueOf(program.size()));
                    }
                    program.add(l);
                }
            } catch (Exception e) {
                error(i + 1, message(e)); return out.toString();
            }
        }

        try {
            collectData();
        } catch (Exception e) {
            error(0, message(e));
            return out.toString();
        }

        for (int pc = 0; pc < program.size(); pc++) {
            if (stopRequested || Thread.currentThread().isInterrupted()) break;
            Line l = program.get(pc);
            currentSourceLine = l.sourceLine;
            String t = l.text.trim();
            String u = t.toUpperCase(Locale.US);
            try {
                if (u.length() == 0 || u.startsWith("REM") || u.startsWith("'")) continue;
                if (u.equals("END")) { if (input instanceof ScreenProvider) ((ScreenProvider)input).flushDisplay(); break; }
                if (isCommand(u, "SCREEN")) screen(t.substring(6).trim());
                else if (isCommand(u, "SCROLL")) scroll(t.substring(6).trim());
                else if (isCommand(u, "ROLL")) roll(t.substring(4).trim());
                 else if (isCommand(u, "CLS")) ((ScreenProvider)input).clearScreen();
                else if (isCommand(u, "BEEP")) beep(t.substring(4).trim());
                else if (isCommand(u, "MAKE")) makeMassive(t.substring(4).trim());
                else if (isCommand(u, "POKE")) pokeMassive(t.substring(4).trim());
                else if (u.startsWith("PLAY!")) play(t.substring(5).trim(), false);
                else if (isCommand(u, "PLAY")) play(t.substring(4).trim(), true);
                else if (isCommand(u, "PRINT")) print(t.substring(5).trim());
                else if (isCommand(u, "DEFFN")) defineFunction(t.substring(5).trim());
                else if (isCommand(u, "DIM")) dimArray(t.substring(3).trim());
                else if (isCommand(u, "DATA")) { /* DATA is collected before execution */ }
                else if (isCommand(u, "READ")) readData(t.substring(4).trim());
                else if (isCommand(u, "RESTORE")) restoreData();
                else if (isCommand(u, "PAUSE")) doPause();
                else if (isCommand(u, "INK")) setInk(t.substring(3).trim());
                else if (isCommand(u, "PAPER")) setPaper(t.substring(5).trim());
                else if (isCommand(u, "TAB")) doTab(t.substring(3).trim());
                else if (isCommand(u, "PLOT")) plot(t.substring(4).trim());
                else if (isCommand(u, "DRAW")) draw(t.substring(4).trim());
                else if (isCommand(u, "LINE")) drawLineAbsolute(t.substring(4).trim());
                else if (isCommand(u, "PAINT")) paintArea(t.substring(5).trim());
                else if (isCommand(u, "PTRIANGLE")) triangle(t.substring(9).trim(), true);
                else if (isCommand(u, "TRIANGLE")) triangle(t.substring(8).trim(), false);
                else if (isCommand(u, "PCIRCLE")) circle(t.substring(7).trim(), true);
                else if (isCommand(u, "CIRCLE")) circle(t.substring(6).trim(), false);
                else if (isCommand(u, "INPUT")) doInput(t.substring(5).trim());
                else if (isCommand(u, "GOTO")) pc = jump(t.substring(4).trim()) - 1;
                else if (isCommand(u, "GOSUB")) { gosubStack.push(Integer.valueOf(pc)); pc = jump(t.substring(5).trim()) - 1; }
                else if (isCommand(u, "RETURN")) {
                    if (gosubStack.empty()) throw new Exception("RETURN without GOSUB");
                    pc = gosubStack.pop().intValue();
                }
                else if (isCommand(u, "IF")) {
                    Integer x = ifCommand(t, pc);
                    if (x != null) pc = x.intValue() - 1;
                } else if (isCommand(u, "FOR")) startFor(t.substring(3).trim(), pc);
                else if (isCommand(u, "NEXT")) pc = next(t.substring(4).trim(), pc);
                else if (isCommand(u, "RANDOMIZE")) randomize(t.substring(9).trim());
                else if (isCommand(u, "LET")) assign(t.substring(3).trim());
                else if (t.indexOf('=') > 0) assign(t);
                else throw new Exception("Unknown command");
            } catch (Exception e) {
                errorSourceLine = l.sourceLine;
                error(l.sourceLine, message(e)); break;
            }
        }
        return out.toString();
    }

    private String message(Exception e) {
        return e.getMessage() == null ? "Syntax error" : e.getMessage();
    }

    private boolean isCommand(String upper, String command) {
        return upper.startsWith(command) &&
            (upper.length() == command.length() ||
             Character.isWhitespace(upper.charAt(command.length())));
    }

    private void beep(String text) throws Exception {
        if (!(input instanceof ScreenProvider)) throw new Exception("Sound unavailable");
        String[] a = splitTopLevel(text, ',');
        if (a.length != 2) throw new Exception("BEEP duration,pitch");
        double duration = new NumExpr(a[0]).parse();
        double pitch = new NumExpr(a[1]).parse();
        if (duration < 0) throw new Exception("Bad BEEP duration");
        ((ScreenProvider)input).beep(duration, pitch);
    }

    private void play(String text, boolean wait) throws Exception {
        if (!(input instanceof ScreenProvider)) throw new Exception("Sound unavailable");
        String[] parts = splitTopLevel(text, ',');
        if (parts.length < 1 || parts.length > 8) throw new Exception("Bad PLAY");
        String[] patterns = new String[parts.length];
        for (int i=0;i<parts.length;i++) patterns[i]=new ValueExpr(parts[i]).parse();
        ((ScreenProvider)input).play(patterns, wait);
    }

    private String[] splitTopLevel(String text, char separator) throws Exception {
        ArrayList<String> r=new ArrayList<String>();
        int start=0, depth=0; boolean quote=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(c=='"') quote=!quote;
            if(!quote){
                if(c=='(') depth++;
                else if(c==')') { if(depth==0) throw new Exception("Unexpected )"); depth--; }
                else if(c==separator && depth==0){
                    String part=text.substring(start,i).trim();
                    if(part.length()==0) throw new Exception("Expression expected");
                    r.add(part); start=i+1;
                }
            }
        }
        if(quote) throw new Exception("Missing quote");
        if(depth!=0) throw new Exception("Missing )");
        String last=text.substring(start).trim();
        if(last.length()==0) throw new Exception("Expression expected");
        r.add(last);
        return r.toArray(new String[r.size()]);
    }

    private void defineFunction(String text) throws Exception {
        String s = text.trim();
        int p = s.indexOf('(');
        if (p <= 0) throw new Exception("Function name expected");
        int close = s.indexOf(')', p + 1);
        if (close < 0) throw new Exception("Missing )");
        int eq = s.indexOf('=', close + 1);
        if (eq < 0) throw new Exception("= expected");

        String name = s.substring(0, p).trim().toUpperCase(Locale.US);
        String param = s.substring(p + 1, close).trim().toUpperCase(Locale.US);
        String body = s.substring(eq + 1).trim();
        if (!name.matches("[A-Z][A-Z0-9_]*")) throw new Exception("Bad function name");
        if (!param.matches("[A-Z][A-Z0-9_]*")) throw new Exception("Bad parameter");
        if (body.length() == 0) throw new Exception("Function expression expected");
        userFunctions.put(name, new UserFunction(param, body));
    }

    private void doInput(String text) throws Exception {
        String raw = text == null ? "" : text.trim();
        String name = raw;
        String prompt = null;

        // Optional prompt syntax: INPUT "Prompt";VARIABLE
        int semi = -1;
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') quote = !quote;
            else if (!quote) {
                if (c == '(') depth++;
                else if (c == ')' && depth > 0) depth--;
                else if (c == ';' && depth == 0) {
                    if (semi >= 0) throw new Exception("Bad INPUT syntax");
                    semi = i;
                }
            }
        }
        if (quote) throw new Exception("Missing quote");
        if (depth != 0) throw new Exception("Missing )");
        if (semi >= 0) {
            prompt = new ValueExpr(raw.substring(0, semi).trim()).parse();
            name = raw.substring(semi + 1).trim();
            if (prompt == null) prompt = "";
            if (name.length() == 0) throw new Exception("Variable expected");
        }

        name = normalizeVariable(name);
        String shownPrompt = prompt == null ? name : prompt;
        String v = input == null ? "" : input.input(shownPrompt);
        if (v == null) v = "";
        if (name.endsWith("$")) strings.put(name, v);
        else {
            try { numbers.put(name, Double.valueOf(v.trim())); }
            catch (Exception e) { throw new Exception("Number expected"); }
        }
    }

    private String normalizeVariable(String name) throws Exception {
        name = name.trim().toUpperCase(Locale.US);
        if (!name.matches("[A-Z][A-Z0-9_]*\\$?"))
            throw new Exception("Bad variable name");
        return name;
    }

    private Line parseLine(String s, int source) throws Exception {
        String t = s.trim(), rest;
        int p = 0;
        while (p < t.length() && Character.isDigit(t.charAt(p))) p++;
        if (p > 0 && (p == t.length() || Character.isWhitespace(t.charAt(p)))) {
            rest = t.substring(p).trim();
            return new Line(Integer.parseInt(t.substring(0, p)), rest, source);
        }
        return new Line(-1, t, source);
    }

    private int jump(String s) throws Exception {
        int n = (int)new NumExpr(s).parse();
        Integer pc = labels.get(Integer.valueOf(n));
        if (pc == null) throw new Exception("No such line: " + n);
        return pc.intValue();
    }

    private Integer ifCommand(String t, int pc) throws Exception {
        String body = t.substring(2).trim();
        int p = findKeyword(body, "THEN");
        if (p < 0) throw new Exception("THEN expected");
        if (!condition(body.substring(0, p).trim())) return null;

        String a = body.substring(p + 4).trim();
        String u = a.toUpperCase(Locale.US);
        if (isCommand(u, "GOTO")) return Integer.valueOf(jump(a.substring(4).trim()));
        if (a.matches("\\d+")) return Integer.valueOf(jump(a));
        return executeInline(a, pc);
    }

    private Integer executeInline(String t, int pc) throws Exception {
        String u = t.toUpperCase(Locale.US);
        if (u.equals("END")) { if (input instanceof ScreenProvider) ((ScreenProvider)input).flushDisplay(); return Integer.valueOf(program.size()); }
        if (isCommand(u, "GOTO")) return Integer.valueOf(jump(t.substring(4).trim()));
        if (isCommand(u, "GOSUB")) {
            gosubStack.push(Integer.valueOf(pc));
            return Integer.valueOf(jump(t.substring(5).trim()));
        }
        if (isCommand(u, "RETURN")) {
            if (gosubStack.empty()) throw new Exception("RETURN without GOSUB");
            return gosubStack.pop();
        }
        if (isCommand(u, "SCREEN")) screen(t.substring(6).trim());
        else if (isCommand(u, "SCROLL")) scroll(t.substring(6).trim());
        else if (isCommand(u, "ROLL")) roll(t.substring(4).trim());
        else if (isCommand(u, "CLS")) ((ScreenProvider)input).clearScreen();
        else if (isCommand(u, "BEEP")) beep(t.substring(4).trim());
        else if (isCommand(u, "MAKE")) makeMassive(t.substring(4).trim());
        else if (isCommand(u, "POKE")) pokeMassive(t.substring(4).trim());
        else if (u.startsWith("PLAY!")) play(t.substring(5).trim(), false);
        else if (isCommand(u, "PLAY")) play(t.substring(4).trim(), true);
        else if (isCommand(u, "PRINT")) print(t.substring(5).trim());
        else if (isCommand(u, "DEFFN")) defineFunction(t.substring(5).trim());
        else if (isCommand(u, "DIM")) dimArray(t.substring(3).trim());
        else if (isCommand(u, "READ")) readData(t.substring(4).trim());
        else if (isCommand(u, "RESTORE")) restoreData();
        else if (isCommand(u, "PAUSE")) doPause();
        else if (isCommand(u, "INK")) setInk(t.substring(3).trim());
        else if (isCommand(u, "PAPER")) setPaper(t.substring(5).trim());
        else if (isCommand(u, "TAB")) doTab(t.substring(3).trim());
        else if (isCommand(u, "PLOT")) plot(t.substring(4).trim());
        else if (isCommand(u, "DRAW")) draw(t.substring(4).trim());
        else if (isCommand(u, "LINE")) drawLineAbsolute(t.substring(4).trim());
        else if (isCommand(u, "PAINT")) paintArea(t.substring(5).trim());
        else if (isCommand(u, "PTRIANGLE")) triangle(t.substring(9).trim(), true);
        else if (isCommand(u, "TRIANGLE")) triangle(t.substring(8).trim(), false);
        else if (isCommand(u, "PCIRCLE")) circle(t.substring(7).trim(), true);
        else if (isCommand(u, "CIRCLE")) circle(t.substring(6).trim(), false);
        else if (isCommand(u, "INPUT")) doInput(t.substring(5).trim());
        else if (isCommand(u, "FOR")) startFor(t.substring(3).trim(), pc);
        else if (isCommand(u, "NEXT")) return Integer.valueOf(next(t.substring(4).trim(), pc));
        else if (isCommand(u, "RANDOMIZE")) randomize(t.substring(9).trim());
        else if (isCommand(u, "LET")) assign(t.substring(3).trim());
        else if (t.indexOf('=') > 0) assign(t);
        else throw new Exception("Unsupported THEN command");
        return null;
    }

    private boolean condition(String c) throws Exception {
        // All logical conditions use the same numeric expression engine.
        // String comparisons are converted to 0/1 there as well.
        return new NumExpr(c).parse() != 0;
    }

    private class BoolExpr {
        String s; int p;
        BoolExpr(String x){s=x.trim();}
        boolean parse() throws Exception { boolean v=orExpr(); skip(); if(p!=s.length()) throw new Exception("Unexpected: "+s.charAt(p)); return v; }
        boolean orExpr() throws Exception { boolean v=andExpr(); while(eatWord("OR")) { boolean r=andExpr(); v=v||r; } return v; }
        boolean andExpr() throws Exception { boolean v=notExpr(); while(eatWord("AND")) { boolean r=notExpr(); v=v&&r; } return v; }
        boolean notExpr() throws Exception { if(eatWord("NOT")) return !notExpr(); return atom(); }
        boolean atom() throws Exception {
            skip();
            if(eat('(')){ boolean v=orExpr(); if(!eat(')')) throw new Exception("Missing )"); return v; }
            int save=p; String left=side(); String op=operator();
            if(op==null){p=save; return new NumExpr(side()).parse()!=0;}
            String right=side();
            if(isStringExpression(left)||isStringExpression(right)){
                int q=new ValueExpr(left).parse().compareTo(new ValueExpr(right).parse());
                return op.equals("=")?q==0:op.equals("<>")?q!=0:op.equals("<")?q<0:op.equals(">")?q>0:op.equals("<=")?q<=0:q>=0;
            }
            double x=new NumExpr(left).parse(),y=new NumExpr(right).parse();
            return op.equals("=")?x==y:op.equals("<>")?x!=y:op.equals("<")?x<y:op.equals(">")?x>y:op.equals("<=")?x<=y:x>=y;
        }
        String side(){
            skip(); int a=p,d=0; boolean q=false;
            while(p<s.length()){
                char ch=s.charAt(p); if(ch=='"') q=!q;
                if(!q){ if(ch=='(')d++; else if(ch==')'&&d>0)d--; if(d==0&&(ch=='='||ch=='<'||ch=='>'||wordAt("AND")||wordAt("OR"))) break; }
                p++;
            } return s.substring(a,p).trim();
        }
        String operator(){ skip(); if(p+1<s.length()&&(s.startsWith("<>",p)||s.startsWith("<=",p)||s.startsWith(">=",p))){String x=s.substring(p,p+2);p+=2;return x;} if(p<s.length()&&(s.charAt(p)=='='||s.charAt(p)=='<'||s.charAt(p)=='>')) return String.valueOf(s.charAt(p++)); return null; }
        boolean wordAt(String w){int e=p+w.length();return e<=s.length()&&s.regionMatches(true,p,w,0,w.length())&&(p==0||!Character.isLetterOrDigit(s.charAt(p-1)))&&(e==s.length()||!Character.isLetterOrDigit(s.charAt(e)));}
        void skip(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}
        boolean eat(char x){skip();if(p<s.length()&&s.charAt(p)==x){p++;return true;}return false;}
        boolean eatWord(String w){skip();if(wordAt(w)){p+=w.length();return true;}return false;}
    }

    private boolean isStringExpression(String s) {
        return s.indexOf('"') >= 0 || s.indexOf('$') >= 0;
    }

    private void startFor(String s, int pc) throws Exception {
        int eq=s.indexOf('=');
        if(eq<0) throw new Exception("Bad FOR");
        String name=normalizeVariable(s.substring(0,eq));
        if(name.endsWith("$")) throw new Exception("FOR variable must be numeric");
        String rest=s.substring(eq+1).trim();
        int to=findKeyword(rest,"TO");
        if(to<0) throw new Exception("TO expected");
        double start=new NumExpr(rest.substring(0,to).trim()).parse();
        String after=rest.substring(to+2).trim();
        int stepPos=findKeyword(after,"STEP");
        double end,step=1;
        if(stepPos>=0) {
            end=new NumExpr(after.substring(0,stepPos).trim()).parse();
            step=new NumExpr(after.substring(stepPos+4).trim()).parse();
            if(step==0) throw new Exception("STEP cannot be zero");
        } else end=new NumExpr(after).parse();
        numbers.put(name,Double.valueOf(start));
        loops.push(new ForState(name,end,step,pc));
    }

    private int next(String s,int pc)throws Exception{
        if(loops.empty()) throw new Exception("NEXT without FOR");
        ForState f=loops.peek();
        String n=s.trim();
        if(n.length()>0 && !normalizeVariable(n).equals(f.name))
            throw new Exception("Wrong NEXT variable");
        Double old=numbers.get(f.name);
        if(old==null) throw new Exception("Undefined variable: "+f.name);
        double v=old.doubleValue()+f.step;
        numbers.put(f.name,Double.valueOf(v));
        boolean again=f.step>0?v<=f.end:v>=f.end;
        if(again) return f.pc;
        loops.pop(); return pc;
    }

    private void randomize(String s)throws Exception{
        s=s.trim();
        if(s.length()==0) random.setSeed(System.currentTimeMillis());
        else random.setSeed((long)new NumExpr(s).parse());
    }

    private void assign(String t)throws Exception{
        int p=t.indexOf('=');
        if(p<=0) throw new Exception("Assignment expected");
        String lhs=t.substring(0,p).trim();
        String v=t.substring(p+1).trim();

        int lp=lhs.indexOf('(');
        if(lp>0 && lhs.endsWith(")")){
            String n=normalizeVariable(lhs.substring(0,lp));
            String inside=lhs.substring(lp+1,lhs.length()-1);
            String strName = resolveStringArrayName(n);
            if(strName != null){
                strArrays.get(strName)[arrayIndex(strName,inside)]=new ValueExpr(v).parse();
                return;
            }
            if(!n.endsWith("$") && numArrayDims.containsKey(n)){
                numArrays.get(n)[arrayIndex(n,inside)]=Double.valueOf(new NumExpr(v).parse());
                return;
            }
        }

        String n=normalizeVariable(lhs);
        if(n.endsWith("$")) strings.put(n,new ValueExpr(v).parse());
        else numbers.put(n,Double.valueOf(new NumExpr(v).parse()));
    }

    private void print(String s)throws Exception {
        String x=s.trim();
        if(x.toUpperCase(Locale.US).startsWith("AT ")) {
            int semi=x.indexOf(';');
            if(semi<0) throw new Exception("PRINT AT expected ;");
            String pos=x.substring(2,semi).trim();
            int comma=pos.indexOf(',');
            if(comma<0) throw new Exception("PRINT AT row,col expected");
            int col=(int)new NumExpr(pos.substring(0,comma).trim()).parse();
            int row=(int)new NumExpr(pos.substring(comma+1).trim()).parse();
            String body=x.substring(semi+1).trim();

            if(input instanceof ScreenProvider) {
                ((ScreenProvider)input).printAt(row,col,"");
                printPieces(body,false);
            }
            out.append('\n');
            return;
        }

        if(input instanceof ScreenProvider) {
            printPieces(s,true);
        } else {
            out.append(new ValueExpr(s).parse()).append('\n');
        }
    }

    private void printPieces(String text, boolean newlineAtEnd)throws Exception {
        ArrayList<String> parts=new ArrayList<String>();
        ArrayList<Character> separators=new ArrayList<Character>();
        int start=0, depth=0; boolean quote=false;
        char lastTopLevelSeparator=0;
        for(int i=0;i<=text.length();i++){
            char ch=i<text.length()?text.charAt(i):'\0';
            if(ch=='"') quote=!quote;
            if(!quote){ if(ch=='(') depth++; else if(ch==')' && depth>0) depth--; }
            if(i<text.length() && !quote && depth==0 && (ch==';'||ch==',')) lastTopLevelSeparator=ch;
            if(i==text.length() || (!quote && depth==0 && (ch==';'||ch==','))){
                String part=text.substring(start,i).trim();
                if(part.length()>0){ parts.add(part); separators.add(i<text.length()?ch:';'); }
                start=i+1;
            }
        }
        for(int i=0;i<parts.size();i++){
            String part=parts.get(i), u=part.toUpperCase(Locale.US);
            if(u.startsWith("INK ")) setInk(part.substring(4).trim());
            else if(u.startsWith("PAPER ")) setPaper(part.substring(6).trim());
            else if(u.startsWith("TAB ")) doTab(part.substring(4).trim());
            else ((ScreenProvider)input).print(new ValueExpr(part).parse());
            if(i<parts.size()-1 && separators.get(i)==',') ((ScreenProvider)input).print(" ");
        }
        // A semicolon suppresses the final newline only when it is the last
        // top-level character of the PRINT statement. Semicolons between
        // PRINT elements keep the normal newline rule.
        boolean trailingSemicolon = false;
        for (int k = text.length() - 1; k >= 0 && Character.isWhitespace(text.charAt(k)); k--) {}
        int end = text.length() - 1;
        while (end >= 0 && Character.isWhitespace(text.charAt(end))) end--;
        if (end >= 0 && text.charAt(end) == ';') {
            int d = 0; boolean q = false; boolean top = true;
            for (int k = 0; k <= end; k++) {
                char c = text.charAt(k);
                if (c == '"') q = !q;
                else if (!q) {
                    if (c == '(') d++;
                    else if (c == ')') d--;
                }
            }
            trailingSemicolon = !q && d == 0;
        }
        if(newlineAtEnd && !trailingSemicolon) ((ScreenProvider)input).print("\n");
    }

    private void plot(String s)throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String[] a=splitTopLevel(s, ',');
        if(a.length!=2) throw new Exception("PLOT x,y expected");
        int x=(int)new NumExpr(a[0]).parse();
        int y=(int)new NumExpr(a[1]).parse();
        int maxX=((ScreenProvider)input).getScreenX()-1;
        int maxY=((ScreenProvider)input).getScreenY()-1;
        if(x<0||x>maxX||y<0||y>maxY) throw new Exception("PLOT coordinate out of range");
        ((ScreenProvider)input).plot(x,y,inkColor);
    }

    private void draw(String s)throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String[] a=splitTopLevel(s, ',');
        if(a.length!=2) throw new Exception("DRAW x,y expected");
        int dx=(int)new NumExpr(a[0]).parse();
        int dy=(int)new NumExpr(a[1]).parse();
        ((ScreenProvider)input).draw(dx,dy,inkColor);
    }

    private void screen(String s)throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String x=s.trim();
        int comma=x.indexOf(',');
        int mode, charset=0;
        if(comma<0) {
            mode=(int)new NumExpr(x).parse();
        } else {
            if(x.indexOf(',',comma+1)>=0) throw new Exception("SCREEN mode,charset expected");
            mode=(int)new NumExpr(x.substring(0,comma).trim()).parse();
            charset=(int)new NumExpr(x.substring(comma+1).trim()).parse();
        }
        if(mode<0||mode>3) throw new Exception("Bad SCREEN mode");
        if(charset<0||charset>1) throw new Exception("Bad SCREEN charset");
        ((ScreenProvider)input).setScreen(mode, charset);
    }

    private void scroll(String s)throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String[] a=splitTopLevel(s, ',');
        if(a.length!=2) throw new Exception("SCROLL x,y expected");
        int dx=(int)new NumExpr(a[0].trim()).parse();
        int dy=(int)new NumExpr(a[1].trim()).parse();
        ((ScreenProvider)input).scrollView(dx,dy);
    }

    private void roll(String s)throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String[] a=splitTopLevel(s, ',');
        if(a.length!=2) throw new Exception("ROLL x,y expected");
        int dx=(int)new NumExpr(a[0].trim()).parse();
        int dy=(int)new NumExpr(a[1].trim()).parse();
        ((ScreenProvider)input).rollView(dx,dy);
    }

    private int findKeyword(String s,String word){
        String u=s.toUpperCase(Locale.US); boolean quote=false;
        for(int i=0;i<=u.length()-word.length();i++){
            if(u.charAt(i)=='"') quote=!quote;
            if(quote) continue;
            if(u.regionMatches(i,word,0,word.length())){
                boolean left=i==0||Character.isWhitespace(u.charAt(i-1));
                int e=i+word.length();
                boolean right=e==u.length()||Character.isWhitespace(u.charAt(e));
                if(left&&right) return i;
            }
        } return -1;
    }

    private void error(int n,String m){ String e="ERROR "+n+": "+m+"\n"; out.append(e); if(input instanceof ScreenProvider) ((ScreenProvider)input).print(e); }
    private String fmt(double n){
        if(Double.isNaN(n)||Double.isInfinite(n)) return Double.toString(n);
        return n==Math.rint(n)?Long.toString((long)n):Double.toString(n);
    }

    private class ValueExpr {
        String s;
        ValueExpr(String x){s=x.trim();}
        String parse()throws Exception{
            StringBuilder r=new StringBuilder();
            int start=0, depth=0; boolean quote=false;
            for(int i=0;i<=s.length();i++){
                char ch=i<s.length()?s.charAt(i):'\0';
                if(ch=='"') quote=!quote;
                if(!quote){
                    if(ch=='(') depth++;
                    else if(ch==')'&&depth>0) depth--;
                }
                if(i==s.length() || (!quote&&depth==0&&(ch==';'||ch==','))){
                    String part=s.substring(start,i).trim();
                    if(part.length()==0) throw new Exception("Expression expected");
                    r.append(valuePart(part));
                    if(i<s.length()&&ch==',') r.append(' ');
                    start=i+1;
                }
            }
            if(quote) throw new Exception("Missing quote");
            return r.toString();
        }
        String valuePart(String part)throws Exception{
            // String-producing functions are evaluated as strings.
            if(hasStringFunction(part)) return stringFunction(part);

            // String-array element references must be recognized before NumExpr:
            // otherwise NumExpr interprets S$(...) as an unknown function S$.
            String uqPart=part.toUpperCase(Locale.US).trim();
            int arrayLp=uqPart.indexOf('(');
            if(arrayLp>0 && uqPart.endsWith(")")){
                int depth=0, match=-1;
                boolean inQuote=false;
                for(int k=arrayLp;k<uqPart.length();k++){
                    char ch=uqPart.charAt(k);
                    if(ch=='"') inQuote=!inQuote;
                    if(!inQuote){
                        if(ch=='(') depth++;
                        else if(ch==')'){
                            depth--;
                            if(depth==0){ match=k; break; }
                        }
                    }
                }
                // Treat it as an array element only when the first '('
                // closes at the very end of the part. This avoids mistaking
                // S$(0)+S$(1)+S$(2) for a single array reference.
                if(match==uqPart.length()-1){
                    String arrayName=uqPart.substring(0,arrayLp).trim();
                    String strName = resolveStringArrayName(arrayName);
                    if(strName != null){
                        return getStrArray(strName,uqPart.substring(arrayLp+1,match));
                    }
                }
            }

            // Try the whole part as a numeric expression first. This is important
            // for numeric string functions such as VAL("123")+1 and LEN("ABC")+1.
            try { return fmt(new NumExpr(part).parse()); }
            catch(Exception ignored) { }
            StringBuilder r=new StringBuilder();
            int start=0; boolean quote=false; int depth=0;
            for(int i=0;i<=part.length();i++){
                char ch=i<part.length()?part.charAt(i):'\0';
                if(ch=='"') quote=!quote;
                if(!quote){ if(ch=='(')depth++; else if(ch==')'&&depth>0)depth--; }
                if(i==part.length()||(!quote&&depth==0&&ch=='+')){
                    String q=part.substring(start,i).trim();
                    if(q.length()==0) throw new Exception("Expression expected");
                    if(q.startsWith("\"")&&q.endsWith("\"")&&q.length()>=2) r.append(q.substring(1,q.length()-1));
                    else if(hasStringFunction(q)) r.append(stringFunction(q));
                    else {
                        String uq=q.toUpperCase(Locale.US);
                        int lp=uq.indexOf('(');
                        if(lp>0 && uq.endsWith(")")){
                            String n=normalizeVariable(q.substring(0,lp));
                            String strName = resolveStringArrayName(n);
                            if(strName != null){
                                r.append(getStrArray(strName,q.substring(lp+1,q.length()-1)));
                                start=i+1;
                                continue;
                            }
                        }
                        if(uq.endsWith("$")){
                            String n=normalizeVariable(q),v=strings.get(n);
                            if(v==null) throw new Exception("Undefined string: "+n);
                            r.append(v);
                        } else r.append(fmt(new NumExpr(q).parse()));
                    }
                    start=i+1;
                }
            }
            if(quote) throw new Exception("Missing quote");
            return r.toString();
        }
    }

    private double inputNumber(String name) throws Exception {
        if (!(input instanceof InputStateProvider))
            throw new Exception("Input unavailable");
        InputStateProvider p = (InputStateProvider) input;
        if (name.equals("KEY")) return p.getKey();
        if (name.equals("TOUCH")) return p.isTouch() ? 1 : 0;
        if (name.equals("TOUCHX")) return p.getTouchX();
        if (name.equals("TOUCHY")) return p.getTouchY();
        if (name.equals("SCREENX")) return p.getScreenX();
        if (name.equals("SCREENY")) return p.getScreenY();
        if (name.equals("GIRO")) return p.getGiro();
        if (name.equals("GIROX")) return p.giroX();
        if (name.equals("GIROY")) return p.giroY();
        throw new Exception("Unknown input value");
    }


    private int[] parseDims(String spec) throws Exception {
        int lp = spec.indexOf('(');
        int rp = spec.lastIndexOf(')');
        if (lp <= 0 || rp != spec.length()-1) throw new Exception("Bad DIM");
        String inside = spec.substring(lp+1, rp);
        String[] parts = inside.split(",");
        if (parts.length < 1 || parts.length > 2) throw new Exception("Bad DIM");
        int[] d = new int[parts.length];
        for (int i=0;i<parts.length;i++) {
            d[i] = (int)Math.round(new NumExpr(parts[i].trim()).parse());
            if (d[i] < 0) throw new Exception("Bad DIM");
        }
        return d;
    }

    private void dimArray(String spec) throws Exception {
        spec = spec.trim();
        int lp = spec.indexOf('(');
        if (lp <= 0) throw new Exception("Bad DIM");
        String name = normalizeVariable(spec.substring(0, lp).trim());
        int[] d = parseDims(spec);

        if (name.endsWith("$")) {
            int size = d.length == 1 ? d[0]+1 : (d[0]+1)*(d[1]+1);
            strArrays.put(name, new String[size]);
            strArrayDims.put(name, d);
        } else {
            int size = d.length == 1 ? d[0]+1 : (d[0]+1)*(d[1]+1);
            numArrays.put(name, new double[size]);
            numArrayDims.put(name, d);
        }
    }

    private int arrayIndex(String name, String inside) throws Exception {
        int[] d = name.endsWith("$") ? strArrayDims.get(name) : numArrayDims.get(name);
        if (d == null) throw new Exception("Undefined array: " + name);
        String[] ix = inside.split(",");
        if (ix.length != d.length) throw new Exception("Bad array index");
        int a = (int)Math.round(new NumExpr(ix[0].trim()).parse());
        if (a < 0 || a > d[0]) throw new Exception("Array index");
        if (d.length == 1) return a;
        int b = (int)Math.round(new NumExpr(ix[1].trim()).parse());
        if (b < 0 || b > d[1]) throw new Exception("Array index");
        return a * (d[1]+1) + b;
    }

    private double getNumArray(String name, String inside) throws Exception {
        double[] a = numArrays.get(name);
        if (a == null) throw new Exception("Undefined array: " + name);
        return a[arrayIndex(name, inside)];
    }

    private String resolveStringArrayName(String name) {
        if(strArrayDims.containsKey(name)) return name;
        String withDollar = name.endsWith("$") ? name : name + "$";
        if(strArrayDims.containsKey(withDollar)) return withDollar;
        return null;
    }

    private String getStrArray(String name, String inside) throws Exception {
        String[] a = strArrays.get(name);
        if (a == null) throw new Exception("Undefined array: " + name);
        String v = a[arrayIndex(name, inside)];
        return v == null ? "" : v;
    }


    private void collectData() throws Exception {
        dataValues.clear();
        dataPointer = 0;
        for (Line ln : program) {
            String t = ln.text.trim();
            if (t.regionMatches(true, 0, "DATA", 0, 4) &&
                (t.length() == 4 || Character.isWhitespace(t.charAt(4)))) {
                String body = t.substring(4).trim();
                if (!body.isEmpty()) {
                    for (String item : splitDataItems(body)) {
                        dataValues.add(item.trim());
                    }
                }
            }
        }
    }

    private ArrayList<String> splitDataItems(String body) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i=0;i<body.length();i++) {
            char c=body.charAt(i);
            if(c=='"') quoted=!quoted;
            if(c==',' && !quoted){
                out.add(stripDataQuotes(cur.toString().trim()));
                cur.setLength(0);
            } else cur.append(c);
        }
        if(cur.length()>0) out.add(stripDataQuotes(cur.toString().trim()));
        return out;
    }

    private String stripDataQuotes(String x) {
        if(x.length()>=2 && x.charAt(0)=='"' && x.charAt(x.length()-1)=='"')
            return x.substring(1,x.length()-1);
        return x;
    }

    private void readData(String vars) throws Exception {
        String[] targets = splitReadTargets(vars);
        for(String raw: targets){
            String target = raw.trim();
            if(target.length()==0) throw new Exception("Bad READ target");
            if(dataPointer>=dataValues.size()) throw new Exception("Out of DATA");
            String value=dataValues.get(dataPointer++);

            int lp=target.indexOf('(');
            if(lp>0 && target.endsWith(")")){
                String n=normalizeVariable(target.substring(0,lp));
                String inside=target.substring(lp+1,target.length()-1);
                String strName = resolveStringArrayName(n);
                if(strName != null){
                    strArrays.get(strName)[arrayIndex(strName,inside)] = value;
                    continue;
                }
                if(!n.endsWith("$") && numArrayDims.containsKey(n)){
                    numArrays.get(n)[arrayIndex(n,inside)] = Double.valueOf(new NumExpr(value).parse());
                    continue;
                }
                throw new Exception("Bad READ target: "+target);
            }

            String n=normalizeVariable(target);
            if(n.endsWith("$")) strings.put(n,value);
            else numbers.put(n,Double.valueOf(new NumExpr(value).parse()));
        }
    }

    private String[] splitReadTargets(String vars) throws Exception {
        ArrayList<String> out=new ArrayList<String>();
        StringBuilder cur=new StringBuilder();
        int depth=0;
        for(int i=0;i<vars.length();i++){
            char c=vars.charAt(i);
            if(c=='(') depth++;
            else if(c==')'){
                depth--;
                if(depth<0) throw new Exception("Bad READ target");
            }
            if(c==',' && depth==0){
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        if(depth!=0) throw new Exception("Bad READ target");
        out.add(cur.toString());
        return out.toArray(new String[out.size()]);
    }

    private void restoreData() {
        dataPointer=0;
    }


    private void makeMassive(String arg) throws Exception {
        if (!(input instanceof ScreenProvider)) throw new Exception("Storage unavailable");
        String[] a = splitTopLevel(arg, ',');
        if (a.length != 3) throw new Exception("MAKE X,Y,\"NAME\" expected");
        int x=(int)Math.round(new NumExpr(a[0]).parse());
        int y=(int)Math.round(new NumExpr(a[1]).parse());
        String name=new ValueExpr(a[2]).parse();
        if(x<0||x>255||y<0||y>255) throw new Exception("MAKE dimensions 0..255");
        if(name.length()==0) throw new Exception("Bad NBM name");

        String fileName=name;
        if (fileName.toLowerCase(Locale.US).endsWith(".nbm"))
            fileName=fileName.substring(0,fileName.length()-4);
        if (fileName.length()==0) throw new Exception("Bad NBM name");
        fileName=fileName+".nbm";
        if (!makeNamesThisRun.isEmpty() && !makeNamesThisRun.contains(fileName))
            throw new Exception("MAKE: only one NBM file per RUN");

        if (((ScreenProvider)input).makeMassive(x,y,name))
            makeNamesThisRun.add(fileName);
    }

    private void pokeMassive(String arg) throws Exception {
        if (!(input instanceof ScreenProvider)) throw new Exception("Storage unavailable");
        String[] a=splitTopLevel(arg, ',');
        if(a.length!=3) throw new Exception("POKE X,Y,BYTE expected");
        int x=(int)Math.round(new NumExpr(a[0]).parse());
        int y=(int)Math.round(new NumExpr(a[1]).parse());
        int v=(int)Math.round(new NumExpr(a[2]).parse());
        ((ScreenProvider)input).pokeMassive(x,y,v);
    }

    private int clampColor(int c) {
        return c < 0 ? 0 : (c > 255 ? 255 : c);
    }

    private void setInk(String arg) throws Exception {
        inkColor = clampColor((int)Math.round(new NumExpr(arg).parse()));
        if (input instanceof ScreenProvider)
            ((ScreenProvider)input).setInk(inkColor);
    }

    private void setPaper(String arg) throws Exception {
        paperColor = clampColor((int)Math.round(new NumExpr(arg).parse()));
        if (input instanceof ScreenProvider)
            ((ScreenProvider)input).setPaper(paperColor);
    }

    private void doTab(String arg) throws Exception {
        int n = (int)Math.round(new NumExpr(arg).parse());
        if (n < 0) n = 0;
        if (input instanceof ScreenProvider)
            ((ScreenProvider)input).tab(n);
    }

    private void doPause() throws Exception {
        if (input instanceof ScreenProvider) ((ScreenProvider)input).flushDisplay();
        if (input instanceof InputStateProvider) {
            InputStateProvider p = (InputStateProvider)input;
            while (p.getKey() != 0 || p.isTouch()) Thread.sleep(20);
            while (p.getKey() == 0 && !p.isTouch()) Thread.sleep(20);
        }
    }


    private void drawLineAbsolute(String arg) throws Exception {
        String[] a = arg.split(",");
        if(a.length != 2 && a.length != 4) throw new Exception("LINE x2,y2 or LINE x1,y1,x2,y2 expected");
        if(!(input instanceof ScreenProvider)) return;
        ScreenProvider p = (ScreenProvider)input;
        if(a.length == 2){
            int x2=(int)Math.round(new NumExpr(a[0].trim()).parse());
            int y2=(int)Math.round(new NumExpr(a[1].trim()).parse());
            p.drawLineTo(x2,y2);
        } else {
            int x1=(int)Math.round(new NumExpr(a[0].trim()).parse());
            int y1=(int)Math.round(new NumExpr(a[1].trim()).parse());
            int x2=(int)Math.round(new NumExpr(a[2].trim()).parse());
            int y2=(int)Math.round(new NumExpr(a[3].trim()).parse());
            p.drawLine(x1,y1,x2,y2);
        }
    }

    private void circle(String arg, boolean filled) throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String[] a = arg.split(",");
        if(a.length != 3 && a.length != 4)
            throw new Exception((filled ? "PCIRCLE" : "CIRCLE") + " xc,yc,r or xc,yc,rx,ry expected");
        int xc=(int)Math.round(new NumExpr(a[0].trim()).parse());
        int yc=(int)Math.round(new NumExpr(a[1].trim()).parse());
        int rx=(int)Math.round(new NumExpr(a[2].trim()).parse());
        int ry=(a.length==3) ? rx : (int)Math.round(new NumExpr(a[3].trim()).parse());
        if(rx<0) rx=-rx;
        if(ry<0) ry=-ry;
        ((ScreenProvider)input).circle(xc,yc,rx,ry,filled);
    }

    private void paintArea(String arg) throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String[] a=arg.split(",");
        if(a.length!=2) throw new Exception("PAINT x,y expected");
        int x=(int)Math.round(new NumExpr(a[0].trim()).parse());
        int y=(int)Math.round(new NumExpr(a[1].trim()).parse());
        int maxX=((ScreenProvider)input).getScreenX()-1, maxY=((ScreenProvider)input).getScreenY()-1;
        if(x<0||x>maxX||y<0||y>maxY) throw new Exception("PAINT coordinate out of range");
        ((ScreenProvider)input).paintArea(x,y);
    }

    private void triangle(String arg, boolean filled) throws Exception {
        if(!(input instanceof ScreenProvider)) throw new Exception("Screen unavailable");
        String[] a = arg.split(",");
        if(a.length != 6) throw new Exception((filled ? "PTRIANGLE" : "TRIANGLE") + " x1,y1,x2,y2,x3,y3 expected");
        int x1=(int)Math.round(new NumExpr(a[0].trim()).parse());
        int y1=(int)Math.round(new NumExpr(a[1].trim()).parse());
        int x2=(int)Math.round(new NumExpr(a[2].trim()).parse());
        int y2=(int)Math.round(new NumExpr(a[3].trim()).parse());
        int x3=(int)Math.round(new NumExpr(a[4].trim()).parse());
        int y3=(int)Math.round(new NumExpr(a[5].trim()).parse());
        ((ScreenProvider)input).triangle(x1,y1,x2,y2,x3,y3,filled);
    }

    private class NumExpr {
        String s; int p;
        NumExpr(String x){s=x.trim();}

        double parse()throws Exception{
            double v=orExpr(); skip();
            if(p!=s.length()) throw new Exception("Unexpected: "+s.charAt(p));
            return v;
        }
        // Logical expressions are numeric: FALSE=0, TRUE=1.
        // Precedence: OR < AND < comparison < arithmetic.
        double orExpr()throws Exception{
            double v=andExpr();
            while(true){
                skip();
                if(eatWord("OR")){
                    double r=andExpr();
                    v=(v!=0 || r!=0) ? 1.0 : 0.0;
                } else return v;
            }
        }
        double andExpr()throws Exception{
            double v=notExpr();
            while(true){
                skip();
                if(eatWord("AND")){
                    double r=notExpr();
                    v=(v!=0 && r!=0) ? 1.0 : 0.0;
                } else return v;
            }
        }
        double notExpr()throws Exception{
            skip();
            if(eatWord("NOT")) return notExpr()!=0 ? 0.0 : 1.0;
            return comparison();
        }
        double comparison()throws Exception{
            // String comparisons are numeric logic too: TRUE=1, FALSE=0.
            // Keep them here, inside the same expression engine, so constructs
            // such as A=B$="YES" and (A$<>B$) AND (C>10) work everywhere.
            skip();
            int save=p;
            String[] strCmp=findStringComparison();
            if(strCmp!=null){
                String left=strCmp[0], op=strCmp[1], right=strCmp[2];
                p=Integer.parseInt(strCmp[3]);
                String lv=new ValueExpr(left).parse();
                String rv=new ValueExpr(right).parse();
                int q=lv.compareTo(rv);
                boolean b=op.equals("=")?q==0:op.equals("<>")?q!=0:op.equals("<")?q<0:op.equals(">")?q>0:op.equals("<=")?q<=0:q>=0;
                return b?1.0:0.0;
            }
            p=save;
            double v=add();
            skip();
            String op=null;
            if(p+1<s.length() && (s.startsWith("<>",p)||s.startsWith("<=",p)||s.startsWith(">=",p))){
                op=s.substring(p,p+2); p+=2;
            } else if(p<s.length() && (s.charAt(p)=='='||s.charAt(p)=='<'||s.charAt(p)=='>')){
                op=String.valueOf(s.charAt(p++));
            }
            if(op==null) return v;
            double r=add();
            boolean b=op.equals("=") ? v==r : op.equals("<>") ? v!=r : op.equals("<") ? v<r : op.equals(">") ? v>r : op.equals("<=") ? v<=r : v>=r;
            return b ? 1.0 : 0.0;
        }

        // Returns {left, operator, right, newParserPosition} for a top-level
        // string comparison beginning at the current parser position.
        String[] findStringComparison() {
            int start=p, depth=0; boolean quote=false; int opPos=-1, opLen=0;
            for(int i=p;i<s.length();i++){
                char ch=s.charAt(i);
                if(ch=='"') { quote=!quote; continue; }
                if(quote) continue;
                if(ch=='('){ depth++; continue; }
                if(ch==')'){ if(depth>0) depth--; else break; continue; }
                if(depth==0){
                    if(i+1<s.length() && (s.startsWith("<>",i)||s.startsWith("<=",i)||s.startsWith(">=",i))){ opPos=i; opLen=2; break; }
                    if(ch=='='||ch=='<'||ch=='>'){ opPos=i; opLen=1; break; }
                    if(wordAtIndex(i,"AND")||wordAtIndex(i,"OR")) break;
                }
            }
            if(opPos<0) return null;
            String left=s.substring(start,opPos).trim();
            if(left.indexOf('$')<0 && left.indexOf('"')<0) return null;
            int rightStart=opPos+opLen, end=rightStart; depth=0; quote=false;
            for(int i=rightStart;i<s.length();i++){
                char ch=s.charAt(i);
                if(ch=='"') { quote=!quote; end=i+1; continue; }
                if(quote) { end=i+1; continue; }
                if(ch=='('){ depth++; continue; }
                if(ch==')'){ if(depth==0) break; depth--; continue; }
                if(depth==0 && (wordAtIndex(i,"AND")||wordAtIndex(i,"OR"))) break;
                end=i+1;
            }
            String right=s.substring(rightStart,end).trim();
            if(right.length()==0) return null;
            if(right.indexOf('$')<0 && right.indexOf('"')<0) return null;
            return new String[]{left,s.substring(opPos,opPos+opLen),right,String.valueOf(end)};
        }

        boolean wordAtIndex(int i,String word){
            int e=i+word.length();
            return e<=s.length() && s.regionMatches(true,i,word,0,word.length()) &&
                    (i==0 || !Character.isLetterOrDigit(s.charAt(i-1))) &&
                    (e==s.length() || !Character.isLetterOrDigit(s.charAt(e)));
        }
        double add()throws Exception{
            double v=mul();
            while(true){skip(); if(eat('+'))v+=mul(); else if(eat('-'))v-=mul(); else return v;}
        }
        double mul()throws Exception{
            double v=power();
            while(true){
                skip();
                if(eat('*'))v*=power();
                else if(eat('/')){double d=power();if(d==0)throw new Exception("Division by zero");v/=d;}
                else if(eatWord("MOD")){
                    double d=power();
                    if(d==0) throw new Exception("Division by zero");
                    v=v-Math.floor(v/d)*d;
                }
                else if(eat('%')){
                    skip();
                    if(p<s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p)=='.' || Character.isLetter(s.charAt(p)) || s.charAt(p)=='(')){
                        double d=power();
                        v=v*d/100.0;
                    } else {
                        v=v/100.0;
                    }
                }
                else return v;
            }
        }
        // Right-associative exponentiation: 2^3^2 = 2^(3^2).
        double power()throws Exception{
            double v=unary(); skip();
            if(eat('^')){
                double e=power();
                v=Math.pow(v,e);
                if(Double.isNaN(v)||Double.isInfinite(v)) throw new Exception("Math domain error");
            }
            return v;
        }
        double unary()throws Exception{
            skip();
            if(eat('+'))return unary();
            if(eat('-'))return -unary();
            return factor();
        }
        double factor()throws Exception{
            skip();
            if(eat('(')){double v=orExpr();if(!eat(')'))throw new Exception("Missing )");return v;}

            if(p<s.length()&&(Character.isDigit(s.charAt(p))||s.charAt(p)=='.')){
                int a=p++;
                while(p<s.length()&&(Character.isDigit(s.charAt(p))||s.charAt(p)=='.'))p++;
                try{return Double.parseDouble(s.substring(a,p));}
                catch(Exception e){throw new Exception("Bad number");}
            }

            if(p<s.length()&&Character.isLetter(s.charAt(p))){
                int a=p++;
                while(p<s.length()&&(Character.isLetterOrDigit(s.charAt(p))||s.charAt(p)=='_'))p++;
                if(p<s.length() && s.charAt(p)=='$') p++;
                String n=s.substring(a,p).toUpperCase(Locale.US);
                if(n.equals("FN")){
                    skip();
                    if(p>=s.length() || !Character.isLetter(s.charAt(p))) throw new Exception("Function name expected");
                    int fa=p++;
                    while(p<s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p)=='_')) p++;
                    String fn=s.substring(fa,p).toUpperCase(Locale.US);
                    if(!userFunctions.containsKey(fn)) throw new Exception("Unknown function: "+fn);
                    skip();
                    if(!eat('(')) throw new Exception("Missing (");
                    String argText=readArgumentText();
                    double arg=new NumExpr(argText).parse();
                    return callUserFunction(fn,arg);
                }
                if(p<s.length() && s.charAt(p)=='(' && numArrayDims.containsKey(n)){
                    int start = ++p, depth = 1;
                    while(p<s.length() && depth>0){
                        if(s.charAt(p)=='(') depth++;
                        else if(s.charAt(p)==')') depth--;
                        p++;
                    }
                    if(depth!=0) throw new Exception("Missing )");
                    return getNumArray(n, s.substring(start,p-1));
                }

                if(n.equals("PI")) return Math.PI;
                if(n.equals("SCREENY")) return inputNumber("SCREENY");
                if(n.equals("SCREENX")) return inputNumber("SCREENX");
                if(n.equals("GIRO")) return inputNumber("GIRO");
                if(n.equals("GIROX")) return inputNumber("GIROX");
                if(n.equals("GIROY")) return inputNumber("GIROY");
                if(n.equals("TOUCHY")) return inputNumber("TOUCHY");
                if(n.equals("TOUCHX")) return inputNumber("TOUCHX");
                if(n.equals("TOUCH")) return inputNumber("TOUCH");
                if(n.equals("KEY")) return inputNumber("KEY");
                if(n.equals("RND")) return random.nextDouble();

                skip();
                if(n.equals("INKEY$")) throw new Exception("String expression expected");
                if(eat('(')){
                    if(userFunctions.containsKey(n)) {
                        String argText = readArgumentText();
                        double arg = new NumExpr(argText).parse();
                        return callUserFunction(n, arg);
                    }
                    if(n.equals("OPEN")) {
                        String argText=readArgumentText();
                        String[] oa=splitTopLevel(argText, ',');
                        if(oa.length!=3) throw new Exception("OPEN(X,Y,\"NAME\") expected");
                        int ox=(int)Math.round(new NumExpr(oa[0]).parse());
                        int oy=(int)Math.round(new NumExpr(oa[1]).parse());
                        String oname=new ValueExpr(oa[2]).parse();
                        if(ox<0||ox>255||oy<0||oy>255) throw new Exception("OPEN dimensions 0..255");
                        return ((ScreenProvider)input).openMassive(ox,oy,oname);
                    }
                    if(n.equals("PEEK")) {
                        String argText=readArgumentText();
                        String[] pa=splitTopLevel(argText, ',');
                        if(pa.length!=2) throw new Exception("PEEK(X,Y) expected");
                        int px=(int)Math.round(new NumExpr(pa[0].trim()).parse());
                        int py=(int)Math.round(new NumExpr(pa[1].trim()).parse());
                        return ((ScreenProvider)input).peekMassive(px,py);
                    }
                    if(n.equals("POINT")){
                        String argText = readArgumentText();
                        String[] pa = argText.split(",");
                        if(pa.length!=2) throw new Exception("POINT x,y expected");
                        int px=(int)Math.round(new NumExpr(pa[0].trim()).parse());
                        int py=(int)Math.round(new NumExpr(pa[1].trim()).parse());
                        return ((ScreenProvider)input).point(px,py);
                    }
                    if(n.equals("CODE") || n.equals("LEN") || n.equals("VAL")){
                        String argText = readArgumentText();
                        String value = new ValueExpr(argText).parse();
                        if(n.equals("CODE")) return value.length() == 0 ? 0 : value.charAt(0);
                        if(n.equals("LEN")) return value.length();
                        try { return Double.parseDouble(value.trim()); }
                        catch(Exception ex) { return 0; }
                    }
                    if(n.equals("CHR$") || n.equals("STR$"))
                        throw new Exception("String expression expected");
                    double arg=add();
                    if(n.equals("MOD")){
                        if(!eat(',')) throw new Exception("Comma expected");
                        double arg2=add();
                        if(!eat(')')) throw new Exception("Missing )");
                        if(arg2==0) throw new Exception("Division by zero");
                        return arg-Math.floor(arg/arg2)*arg2;
                    }
                    if(!eat(')')) throw new Exception("Missing )");
                    return function(n,arg);
                }

                Double v=numbers.get(n);
                if(v==null) throw new Exception("Undefined variable: "+n);
                return v.doubleValue();
            }
            throw new Exception("Number expected");
        }

        String readArgumentText() throws Exception {
            int start = p;
            int depth = 0;
            boolean quote = false;
            while(p < s.length()){
                char c = s.charAt(p++);
                if(c == '"') quote = !quote;
                if(!quote){
                    if(c == '(') depth++;
                    else if(c == ')'){
                        if(depth == 0) return s.substring(start, p - 1);
                        depth--;
                    }
                }
            }
            throw new Exception("Missing )");
        }

        double callUserFunction(String n, double arg) throws Exception {
            UserFunction f = userFunctions.get(n);
            if (f == null) throw new Exception("Unknown function: " + n);
            Double old = numbers.get(f.param);
            boolean had = numbers.containsKey(f.param);
            numbers.put(f.param, Double.valueOf(arg));
            try {
                return new NumExpr(f.body).parse();
            } finally {
                if (had) numbers.put(f.param, old);
                else numbers.remove(f.param);
            }
        }

        double function(String n,double x)throws Exception{
            if(n.equals("SIN")) return Math.sin(x);
            if(n.equals("COS")) return Math.cos(x);
            if(n.equals("TAN")) return Math.tan(x);
            if(n.equals("COT")){
                double y=Math.tan(x);
                if(Math.abs(y)<1e-15) throw new Exception("Division by zero");
                return 1.0/y;
            }
            if(n.equals("ASN")){
                if(x<-1||x>1) throw new Exception("Math domain error");
                return Math.asin(x);
            }
            if(n.equals("ACS")){
                if(x<-1||x>1) throw new Exception("Math domain error");
                return Math.acos(x);
            }
            if(n.equals("ATN")) return Math.atan(x);
            if(n.equals("EXP")){
                double y=Math.exp(x);
                if(Double.isInfinite(y)) throw new Exception("Math domain error");
                return y;
            }
            if(n.equals("LN")){
                if(x<=0) throw new Exception("Math domain error");
                return Math.log(x);
            }
            if(n.equals("SQR")){
                if(x<0) throw new Exception("Math domain error");
                return Math.sqrt(x);
            }
            if(n.equals("ABS")) return Math.abs(x);
            if(n.equals("INT")) return Math.floor(x);
            if(n.equals("SGN")) return x>0?1:x<0?-1:0;
            throw new Exception("Unknown function: "+n);
        }

        void skip(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}
        boolean eatWord(String word){
            skip();
            int e=p+word.length();
            if(e<=s.length() && s.regionMatches(true,p,word,0,word.length()) &&
                    (p==0 || !Character.isLetterOrDigit(s.charAt(p-1))) &&
                    (e==s.length() || !Character.isLetterOrDigit(s.charAt(e)))){
                p=e; return true;
            }
            return false;
        }
        boolean eat(char c){skip();if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}
    }

    private static class Line {
        int number; String text; int sourceLine;
        Line(int n,String t,int s){number=n;text=t;sourceLine=s;}
    }
    private static class ForState {
        String name; double end,step; int pc;
        ForState(String n,double e,double st,int p){name=n;end=e;step=st;pc=p;}
    }
    private static class UserFunction {
        String param; String body;
        UserFunction(String p, String b){param=p;body=b;}
    }

    private String stringFunction(String expr) throws Exception {
        String e = expr.trim();
        String u = e.toUpperCase(Locale.US);

        if (u.startsWith("CHR$(") && e.endsWith(")")) {
            double n = new NumExpr(e.substring(5, e.length() - 1)).parse();
            return String.valueOf((char)(((int)Math.round(n)) & 255));
        }
        if (u.startsWith("STR$(") && e.endsWith(")")) {
            double n = new NumExpr(e.substring(5, e.length() - 1)).parse();
            if (n == Math.rint(n)) return Long.toString((long)n);
            return Double.toString(n);
        }
        if (u.equals("TIME$")) return currentTimeString();
        if (u.equals("DATE$")) return currentDateString();
        if (u.equals("INKEY$")) {
            int k = (int)inputNumber("KEY");
            return k == 0 ? "" : String.valueOf((char)(k & 255));
        }
        if (e.length() >= 2 && e.charAt(0) == '"' && e.charAt(e.length() - 1) == '"') {
            return e.substring(1, e.length() - 1);
        }
        if (e.toUpperCase(Locale.US).endsWith("$")) {
            String n = normalizeVariable(e);
            String v = strings.get(n);
            if (v == null) throw new Exception("Undefined string: " + n);
            return v;
        }
        return e;
    }

    private boolean hasStringFunction(String expr) {
        String u = expr.trim().toUpperCase(Locale.US);
        return u.startsWith("CHR$(") || u.startsWith("STR$(") || u.equals("INKEY$") || u.equals("TIME$") || u.equals("DATE$");
    }


    private String currentTimeString(){
        java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

    private String currentDateString(){
        java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat("dd:MM:yyyy", java.util.Locale.US);
        return f.format(new java.util.Date());
    }

}
