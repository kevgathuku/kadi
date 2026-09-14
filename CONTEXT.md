# Kadi

A multiplayer card game (Kenyan "Poker"): match suit or rank, shed your hand, declare Kadi to win.

## Language

**Username**:
The unique, visible handle for a player. Shown everywhere in the UI.
_Avoid_: email, display name

**Combo**:
Multiple cards of the same rank played together; the first must match the top card.
_Avoid_: combination

**Penalty**:
An obligation created by a 2 (draw 2) or 3 (draw 3) on the next player in turn direction.

**Accept**:
Resolving a penalty by drawing the owed cards; the turn then passes.

**Block**:
Avoiding a penalty with an Ace (clears it) or the same penalty rank (a 2 answers a 2).

**Transfer**:
Passing a penalty on by playing the same penalty rank; the next player owes it.

**Action Suit**:
A suit requested by a regular Ace play; every following card must match it.

**Blocked Suit**:
The suit of a penalty card preserved when an Ace blocks; 2s and 3s may still bypass by rank.
_Avoid_: imposed suit

**Question**:
Q or 8 cards played asking for an answer.

**Answer**:
Non-question card(s) completing a question in the same play. An unanswered question forces the asker to draw and holds the turn.

**Kadi**:
Declared intent to finish; empty hand while declared wins the game.
_Avoid_: uno

**Cardless**:
Empty hand with no valid finish (an action card went out last); the player must draw instead of winning. K and J resolve against the table size: in 2-player games skip/reverse arithmetic wraps back to the same player.

**Finished**:
The game status once won; no further actions allowed.

**Winner**:
The player who emptied their hand while declared Kadi.

**Skip**:
A Jack passing the turn past N players, honouring direction.

**Reverse**:
A King flipping turn direction between clockwise and counter-clockwise.

**Draw**:
Taking the top card of the deck into hand; the turn then passes.
