package com.simonrowe.resume;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

/**
 * Turns the markdown held in a job's description into the flat block structure the CV
 * renders.
 *
 * <p>This exists because commonmark's {@code TextContentRenderer} — what the CV used
 * before — flattens everything: sub-headings become ordinary lines and bullets come out
 * as literal asterisks. The sub-headings are the thing that makes a twenty-bullet role
 * readable, so they are kept and rendered as bold run-in labels.
 *
 * <p>Nesting is deliberately flattened. A CV bullet indented three levels deep is a
 * formatting accident rather than an intent, and the CMS content does not use it.
 */
@Component
public class ResumeMarkdownParser {

  private final Parser parser = Parser.builder().build();

  /**
   * Parses markdown into renderable blocks.
   *
   * @param markdown CMS markdown, possibly null or blank
   * @return the blocks, in document order; empty when there is nothing to render
   */
  public List<ResumeBlock> parse(final String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return List.of();
    }
    BlockCollector collector = new BlockCollector();
    parser.parse(markdown).accept(collector);
    return collector.blocks.stream().filter(block -> !block.isBlank()).toList();
  }

  private static final class BlockCollector extends AbstractVisitor {

    private final List<ResumeBlock> blocks = new ArrayList<>();

    /** Non-null while inside a list item, so paragraphs attach to the bullet. */
    private List<ResumeTextRun> currentBullet;

    @Override
    public void visit(final Heading heading) {
      blocks.add(new ResumeBlock(ResumeBlock.Kind.HEADING, collectRuns(heading)));
    }

    @Override
    public void visit(final BulletList bulletList) {
      visitChildren(bulletList);
    }

    @Override
    public void visit(final OrderedList orderedList) {
      visitChildren(orderedList);
    }

    @Override
    public void visit(final ListItem listItem) {
      if (currentBullet != null) {
        // A nested item: flatten into the enclosing bullet rather than opening a new
        // one, so the text stays with the point it qualifies.
        appendSeparator(currentBullet);
        visitChildren(listItem);
        return;
      }
      currentBullet = new ArrayList<>();
      visitChildren(listItem);
      blocks.add(new ResumeBlock(ResumeBlock.Kind.BULLET, currentBullet));
      currentBullet = null;
    }

    @Override
    public void visit(final Paragraph paragraph) {
      if (currentBullet != null) {
        appendSeparator(currentBullet);
        currentBullet.addAll(collectRuns(paragraph));
        return;
      }
      blocks.add(new ResumeBlock(ResumeBlock.Kind.PARAGRAPH, collectRuns(paragraph)));
    }

    @Override
    public void visit(final BlockQuote blockQuote) {
      // Rendered as ordinary prose; a CV has no use for a quote treatment.
      visitChildren(blockQuote);
    }

    /**
     * Separates two flattened fragments. Idempotent: a nested list item enters through
     * both {@code visit(ListItem)} and the {@code visit(Paragraph)} inside it, and
     * adding a space at each would leave a visible double space in the rendered bullet.
     */
    private static void appendSeparator(final List<ResumeTextRun> runs) {
      if (runs.isEmpty()) {
        return;
      }
      String text = runs.get(runs.size() - 1).text();
      if (!text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))) {
        runs.add(ResumeTextRun.plain(" "));
      }
    }

    private static List<ResumeTextRun> collectRuns(final Node node) {
      InlineCollector inline = new InlineCollector();
      Node child = node.getFirstChild();
      while (child != null) {
        child.accept(inline);
        child = child.getNext();
      }
      return inline.runs;
    }
  }

  /**
   * Collects the styled runs inside one block. Links contribute their text only — a CV
   * is printed as often as it is clicked, and a bare URL in the middle of a bullet reads
   * worse than the words around it.
   */
  private static final class InlineCollector extends AbstractVisitor {

    private final List<ResumeTextRun> runs = new ArrayList<>();

    private boolean bold;
    private boolean italic;

    @Override
    public void visit(final Text text) {
      add(text.getLiteral());
    }

    @Override
    public void visit(final Code code) {
      add(code.getLiteral());
    }

    @Override
    public void visit(final StrongEmphasis strongEmphasis) {
      boolean previous = bold;
      bold = true;
      visitChildren(strongEmphasis);
      bold = previous;
    }

    @Override
    public void visit(final Emphasis emphasis) {
      boolean previous = italic;
      italic = true;
      visitChildren(emphasis);
      italic = previous;
    }

    @Override
    public void visit(final Link link) {
      visitChildren(link);
    }

    @Override
    public void visit(final SoftLineBreak softLineBreak) {
      add(" ");
    }

    @Override
    public void visit(final HardLineBreak hardLineBreak) {
      add(" ");
    }

    private void add(final String text) {
      if (text.isEmpty()) {
        return;
      }
      if (!runs.isEmpty()) {
        ResumeTextRun last = runs.get(runs.size() - 1);
        if (last.bold() == bold && last.italic() == italic) {
          runs.set(runs.size() - 1,
              new ResumeTextRun(last.text() + text, bold, italic));
          return;
        }
      }
      runs.add(new ResumeTextRun(text, bold, italic));
    }
  }
}
