package epic.everything

import epic.trees._
import epic.coref.CorefInstance
import epic.sequences.Segmentation
import epic.trees.StandardTreeProcessor

/**
 * 
 * @author dlwh
 */
case class ProcessedDocument(sentences: IndexedSeq[ProcessedSentence],
                             coref: CorefInstance,
                             id: String="") {

}

case class ProcessedSentence(words: IndexedSeq[String],
                             tree: BinarizedTree[AnnotatedLabel],
                             ner: Segmentation[NERType.Value, String],
                             speaker: Option[String],
                             id: String="") {
  def length= words.length
}


object ProcessedDocument {
  case class Factory(treeProcessor: StandardTreeProcessor, corefProcessor: CorefInstance.Factory) extends (Document=>ProcessedDocument) {

    def apply(d: Document):ProcessedDocument = {
      val newSentences = for(s <- d.sentences) yield {
        val seg = s.nerSegmentation
        var tree = treeProcessor(s.tree.map(_.treebankString))
        tree = UnaryChainRemover.removeUnaryChains(tree)

        ProcessedSentence(s.words, tree, seg, s.speaker, s.id)
      }

      val coref = corefProcessor(d)

      ProcessedDocument(newSentences, coref, d.id)
    }

  }
}