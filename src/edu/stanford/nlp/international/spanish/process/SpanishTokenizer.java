package edu.stanford.nlp.international.spanish.process;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.CoreAnnotations.OriginalTextAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.CoreAnnotations.ParentAnnotation;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.process.TokenizerFactory;
import edu.stanford.nlp.process.AbstractTokenizer;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.LexedTokenFactory;
import edu.stanford.nlp.process.Tokenizer;
import edu.stanford.nlp.process.WordTokenFactory;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.international.spanish.SpanishVerbStripper;

/**
 * Tokenizer for raw Spanish text. This tokenization scheme is a derivative
 * of PTB tokenization, but with extra rules for Spanish contractions and 
 * assimilations. It is based heavily on the FrenchTokenizer.
 * <p>
 * The tokenizer tokenizes according to the modified AnCora corpus tokenization
 * standards, so the rules are a little different from PTB.
 * </p>
 * <p>
 * A single instance of a Spanish Tokenizer is not thread safe, as it
 * uses a non-threadsafe JFlex object to do the processing.  Multiple
 * instances can be created safely, though.  A single instance of a
 * SpanishTokenizerFactory is also not thread safe, as it keeps its
 * options in a local variable.
 * </p>
 *
 * @author Ishita Prasad
 */
public class SpanishTokenizer<T extends HasWord> extends AbstractTokenizer<T> {

  // The underlying JFlex lexer
  private final SpanishLexer lexer;

  // Internal fields compound splitting
  private final boolean splitCompounds;
  private final boolean splitVerbs;
  private final boolean splitContractions;
  private final boolean splitAny;
  private List<CoreLabel> compoundBuffer;

  // Produces the tokenization for parsing used by AnCora (fixed) */
  public static final String ANCORA_OPTIONS = "ptb3Ellipsis=true,normalizeParentheses=true,ptb3Dashes=false,splitAll=true";

  /**
   * Constructor.
   *
   * @param r
   * @param tf
   * @param lexerProperties
   * @param splitCompounds
   */
  public SpanishTokenizer(Reader r, LexedTokenFactory<T> tf, Properties lexerProperties, boolean splitCompounds, boolean splitVerbs, boolean splitContractions) {
    lexer = new SpanishLexer(r, tf, lexerProperties);
    this.splitCompounds = splitCompounds;
    this.splitVerbs = splitVerbs;
    this.splitContractions = splitContractions;
    this.splitAny = (splitCompounds || splitVerbs || splitContractions);

    if (splitAny) compoundBuffer = Generics.newLinkedList();
  }

  @Override
  @SuppressWarnings("unchecked")
  protected T getNext() {
    try {
      T nextToken = null;
      // Depending on the orthographic normalization options,
      // some tokens can be obliterated. In this case, keep iterating
      // until we see a non-zero length token.
      do {
	  nextToken = (splitAny && compoundBuffer.size() > 0) ?
	      (T) compoundBuffer.remove(0) :
              (T) lexer.next();
      } while (nextToken != null && nextToken.word().length() == 0);

      // Check for compounds to split
      if (splitAny && nextToken instanceof CoreLabel) {
        CoreLabel cl = (CoreLabel) nextToken;
	if (cl.containsKey(ParentAnnotation.class)) {
	    if(splitCompounds && cl.get(ParentAnnotation.class).equals(SpanishLexer.COMPOUND_ANNOTATION))
		nextToken = (T) processCompound(cl);
	    else if (splitVerbs && cl.get(ParentAnnotation.class).equals(SpanishLexer.VB_PRON_ANNOTATION))
		nextToken = (T) processVerb(cl);
	    else if (splitContractions && cl.get(ParentAnnotation.class).equals(SpanishLexer.CONTR_ANNOTATION))
		nextToken = (T) processContraction(cl);
	}
      }

      return nextToken;

    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
  }


  /* Copies the CoreLabel cl with the new word part */
  private CoreLabel copyCoreLabel(CoreLabel cl, String part) {
      CoreLabel newLabel = new CoreLabel(cl);
      newLabel.setWord(part);
      newLabel.setValue(part);
      newLabel.set(OriginalTextAnnotation.class, part);
      return newLabel;
  }

  /**
   * Handles contractions like del and al, marked by the lexer
   */
  private CoreLabel processContraction(CoreLabel cl) {
      cl.remove(ParentAnnotation.class);
      String word = cl.word();
      String first = word.substring(0, word.length()-1);
      String second;
      char lastChar = word.charAt(word.length()-1);

      if(Character.isLowerCase(lastChar))
	  second = "el";
      else second = "EL";
   
      compoundBuffer.add(copyCoreLabel(cl, second));
      return copyCoreLabel(cl, first);
  }

  /**
   * Handles verbs with attached suffixes, marked by the lexer
   */
  private CoreLabel processVerb(CoreLabel cl) {
      cl.remove(ParentAnnotation.class);
      Pair<String, List<String>> parts = SpanishVerbStripper.separatePronouns(cl.word());
      if (parts == null)
	  return cl;
      for(String pronoun : parts.second())
	  compoundBuffer.add(copyCoreLabel(cl, pronoun));
      return copyCoreLabel(cl, parts.first());
  }

  /**
   * Splits a compound marked by the lexer.
   */
  private CoreLabel processCompound(CoreLabel cl) {
    cl.remove(ParentAnnotation.class);
    String[] parts = cl.word().replaceAll("\\-", " - ").split("\\s+");
    for (String part : parts) {
      CoreLabel newLabel = new CoreLabel(cl);
      newLabel.setWord(part);
      newLabel.setValue(part);
      newLabel.set(OriginalTextAnnotation.class, part);
      compoundBuffer.add(newLabel);
    }
    return compoundBuffer.remove(0);
  }

  /**
   * A factory for Spanish tokenizer instances.
   *
   * @author Spence Green
   *
   * @param <T>
   */
  public static class SpanishTokenizerFactory<T extends HasWord> implements TokenizerFactory<T>, Serializable  {

    private static final long serialVersionUID = 946818805507187330L;

    protected final LexedTokenFactory<T> factory;
    protected Properties lexerProperties = new Properties();
    protected boolean splitCompoundOption = false;
    protected boolean splitVerbOption = false;
    protected boolean splitContractionOption = false;

    public static TokenizerFactory<CoreLabel> newTokenizerFactory() {
      return new SpanishTokenizerFactory<CoreLabel>(new CoreLabelTokenFactory());
    }

    /**
     * Constructs a new PTBTokenizer that returns Word objects and
     * uses the options passed in.
     * THIS METHOD IS INVOKED BY REFLECTION BY SOME OF THE JAVANLP
     * CODE TO LOAD A TOKENIZER FACTORY.  IT SHOULD BE PRESENT IN A
     * TokenizerFactory.
     * todo [cdm 2013]: But we should change it to a method that can return any kind of Label and return CoreLabel here
     *
     * @param options A String of options
     * @return A TokenizerFactory that returns Word objects
     */
    public static TokenizerFactory<Word> newWordTokenizerFactory(String options) {
      return new SpanishTokenizerFactory<Word>(new WordTokenFactory(), options);
    }


    private SpanishTokenizerFactory(LexedTokenFactory<T> factory) {
      this.factory = factory;
    }

    private SpanishTokenizerFactory(LexedTokenFactory<T> factory, String options) {
      this(factory);
      setOptions(options);
    }

    @Override
    public Iterator<T> getIterator(Reader r) {
      return getTokenizer(r);
    }

    @Override
    public Tokenizer<T> getTokenizer(Reader r) {
	return new SpanishTokenizer<T>(r, factory, lexerProperties, splitCompoundOption, splitVerbOption, splitContractionOption);
    }

    /**
     * Set underlying tokenizer options.
     *
     * @param options A comma-separated list of options
     */
    @Override
    public void setOptions(String options) {
      String[] optionList = options.split(",");
      for (String option : optionList) {
        String[] fields = option.split("=");
        if (fields.length == 1) {
	  if (fields[0].equals("splitAll")) {
	    splitCompoundOption = true;
	    splitVerbOption = true;
	    splitContractionOption = true;
	  } else if (fields[0].equals("splitCompounds")) {
	      splitCompoundOption = true;
          } else if (fields[0].equals("splitVerbs")){
	      splitVerbOption = true;
	  } else if (fields[0].equals("splitContractions")) {
	      splitContractionOption = true;
	  } else {
            lexerProperties.put(option, "true");
          }

        } else if (fields.length == 2) {
	    if (fields[0].equals("splitAll")) {
	      splitCompoundOption = Boolean.valueOf(fields[1]);
	      splitVerbOption = Boolean.valueOf(fields[1]);
	      splitContractionOption = Boolean.valueOf(fields[1]);
	  } else if (fields[0].equals("splitCompounds")) {
	      splitCompoundOption = Boolean.valueOf(fields[1]);
	  } else if (fields[0].equals("splitVerbs")){
	      splitVerbOption = Boolean.valueOf(fields[1]);
	  } else if (fields[0].equals("splitContractions")) {
	      splitContractionOption = Boolean.valueOf(fields[1]);
	  } else {
	      lexerProperties.put(fields[0], fields[1]);
          }

	  /* TODOS ALL OVER THE PLACE */

        } else {
          System.err.printf("%s: Bad option %s%n", this.getClass().getName(), option);
        }
      }
    }

    @Override
    public Tokenizer<T> getTokenizer(Reader r, String extraOptions) {
      setOptions(extraOptions);
      return getTokenizer(r);
    }

  } // end static class FrenchTokenizerFactory


  /**
   * Returns a factory for FrenchTokenizer.
   * THIS IS NEEDED FOR CREATION BY REFLECTION.
   */
  public static TokenizerFactory<CoreLabel> factory() {
    return SpanishTokenizerFactory.newTokenizerFactory();
  }


  /** Returns a factory for SpanishTokenizer that replicates the tokenization of
   * AnCora (fixed).
   */
  public static TokenizerFactory<CoreLabel> ancoraFactory() {
      TokenizerFactory<CoreLabel> tf = SpanishTokenizerFactory.newTokenizerFactory();
      tf.setOptions(ANCORA_OPTIONS);
      return tf;
  }

  private static String usage() {
    StringBuilder sb = new StringBuilder();
    String nl = System.getProperty("line.separator");
    sb.append(String.format("Usage: java %s [OPTIONS] < file%n%n", SpanishTokenizer.class.getName()));
    sb.append("Options:").append(nl);
    sb.append("   -help          : Print this message.").append(nl);
    // sb.append("   -ftb           : Tokenization for experiments in Green et al. (2011).").append(nl);
    sb.append("   -ancora        : Tokenization style of AnCora (fixed).").append(nl);
    sb.append("   -lowerCase     : Apply lowercasing.").append(nl);
    sb.append("   -encoding type : Encoding format.").append(nl);
    sb.append("   -orthoOpts str : Orthographic options (see SpanishLexer.java)").append(nl);
    return sb.toString();
  }

  private static Map<String,Integer> argOptionDefs() {
    Map<String,Integer> argOptionDefs = Generics.newHashMap();
    argOptionDefs.put("help", 0);
    argOptionDefs.put("ftb", 0);
    argOptionDefs.put("ancora", 0);
    argOptionDefs.put("lowerCase", 0);
    argOptionDefs.put("encoding", 1);
    argOptionDefs.put("orthoOpts", 1);
    return argOptionDefs;
  }

  /**
   * A fast, rule-based tokenizer for Modern Standard French.
   * Performs punctuation splitting and light tokenization by default.
   * <p>
   * Currently, this tokenizer does not do line splitting. It assumes that the input
   * file is delimited by the system line separator. The output will be equivalently
   * delimited.
   * </p>
   *
   * @param args
   */
  public static void main(String[] args) {
    final Properties options = StringUtils.argsToProperties(args, argOptionDefs());
    if (options.containsKey("help")) {
      System.err.println(usage());
      return;
    }

    // Lexer options
    final TokenizerFactory<CoreLabel> tf = options.containsKey("ancora") ?
        SpanishTokenizer.ancoraFactory() : SpanishTokenizer.factory();
    String orthoOptions = options.getProperty("orthoOpts", "");
    tf.setOptions(orthoOptions);

    // When called from this main method, split on newline. No options for
    // more granular sentence splitting.
    tf.setOptions("tokenizeNLs");

    // Other options
    final String encoding = options.getProperty("encoding", "UTF-8");
    final boolean toLower = PropertiesUtils.getBool(options, "lowerCase", false);

    // Read the file from stdin
    int nLines = 0;
    int nTokens = 0;
    final long startTime = System.nanoTime();
    try {
      Tokenizer<CoreLabel> tokenizer = tf.getTokenizer(new InputStreamReader(System.in, encoding));
      boolean printSpace = false;
      while (tokenizer.hasNext()) {
        ++nTokens;
        String word = tokenizer.next().word();
        if (word.equals(SpanishLexer.NEWLINE_TOKEN)) {
          ++nLines;
          printSpace = false;
          System.out.println();
        } else {
          if (printSpace) System.out.print(" ");
          String outputToken = toLower ? word.toLowerCase(Locale.FRENCH) : word;
          System.out.print(outputToken);
          printSpace = true;
        }
      }
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
    long elapsedTime = System.nanoTime() - startTime;
    double linesPerSec = (double) nLines / (elapsedTime / 1e9);
    System.err.printf("Done! Tokenized %d lines (%d tokens) at %.2f lines/sec%n", nLines, nTokens, linesPerSec);
  } // end main()

}