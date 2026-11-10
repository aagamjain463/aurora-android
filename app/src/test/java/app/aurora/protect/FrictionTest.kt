package app.aurora.protect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Friction math problems are real, scale with difficulty, and only the exact answer unlocks. */
class FrictionTest {
    @Test
    fun onlyTheExactAnswerUnlocks() {
        val p = MathProblem("17 × 6 + 23", 125)
        assertTrue(checkAnswer(p, "125"))
        assertTrue(checkAnswer(p, " 125 "))
        assertFalse(checkAnswer(p, "124"))
        assertFalse(checkAnswer(p, ""))
        assertFalse(checkAnswer(p, "12 5x"))
    }

    @Test
    fun problemsAreCorrectAtEveryDifficulty() {
        val random = Random(42)
        repeat(200) {
            for (d in Difficulty.entries) {
                val p = mathProblem(d, random)
                val parts = p.text.split(" ")
                val value = when (parts.size) {
                    3 -> parts[0].toInt() + parts[2].toInt()
                    else -> {
                        val product = parts[0].toInt() * parts[2].toInt()
                        if (parts[3] == "+") product + parts[4].toInt() else product - parts[4].toInt()
                    }
                }
                assertTrue(p.text, value == p.answer)
                assertTrue(p.answer > 10)
            }
        }
    }

    @Test
    fun onlyTheExactPhraseEndsTheWritingTask() {
        assertTrue(phraseMatches("I choose focus", "I choose focus"))
        assertTrue(phraseMatches("I choose focus", "  i   CHOOSE\tfocus "))
        assertFalse(phraseMatches("I choose focus", "I choose focus."))
        assertFalse(phraseMatches("I choose focus", "focus"))
        // A Shield saved without words can never be ended by typing nothing.
        assertFalse(phraseMatches("", ""))
        assertFalse(phraseMatches("   ", "   "))
    }

    @Test
    fun harderProblemsHaveBiggerAnswers() {
        val random = Random(7)
        val easy = (1..100).map { mathProblem(Difficulty.Easy, random).answer }.average()
        val hard = (1..100).map { mathProblem(Difficulty.Hard, random).answer }.average()
        assertTrue(hard > easy * 3)
    }

    @Test
    fun writtenIntentionNeedsItsWords() {
        // No words, no Shield: the editor won't save one, so no Shield ends with just any text.
        assertFalse(FrictionSettings(task = FrictionTask.Intention).ready)
        assertFalse(FrictionSettings(task = FrictionTask.Intention, phrase = "   ").ready)
        assertTrue(FrictionSettings(task = FrictionTask.Intention, phrase = "I choose my time").ready)
        assertTrue(FrictionSettings(task = FrictionTask.Math).ready)
        assertFalse(phraseMatches("I choose my time", "any two words"))
    }
}
