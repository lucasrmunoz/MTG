/**
 * Glossary of Magic keyword abilities and keyword actions.
 *
 * Definitions are written in reminder-text style rather than quoting the Comprehensive Rules —
 * no public API serves keyword definitions, so the list is maintained here by hand. It covers
 * every evergreen keyword plus the widely played set mechanics; it is not the exhaustive list of
 * everything ever printed.
 */

export interface KeywordEntry {
  name: string;
  definition: string;
}

/** Alphabetical, so the full glossary reads like a dictionary. */
export const KEYWORDS: readonly KeywordEntry[] = [
  {
    name: "Adapt",
    definition:
      "If this creature has no +1/+1 counters on it, put the stated number of +1/+1 counters on it.",
  },
  {
    name: "Affinity",
    definition:
      "This spell costs {1} less to cast for each permanent you control of the stated type, such as artifacts.",
  },
  {
    name: "Amass",
    definition:
      "Put the stated number of +1/+1 counters on an Army you control. If you don't control one, create a 0/0 Army creature token first.",
  },
  {
    name: "Annihilator",
    definition:
      "Whenever this creature attacks, the defending player sacrifices the stated number of permanents.",
  },
  {
    name: "Ascend",
    definition:
      "If you control ten or more permanents, you get the city's blessing for the rest of the game.",
  },
  {
    name: "Battle cry",
    definition:
      "Whenever this creature attacks, each other attacking creature gets +1/+0 until end of turn.",
  },
  {
    name: "Bestow",
    definition:
      "You may cast this card for its bestow cost as an Aura enchanting a creature. If the enchanted creature leaves, this becomes a creature again.",
  },
  {
    name: "Blitz",
    definition:
      "You may cast this creature for its blitz cost. It gains haste and \"When this creature dies, draw a card,\" and it's sacrificed at the beginning of the next end step.",
  },
  {
    name: "Buyback",
    definition:
      "You may pay the buyback cost as you cast this spell. If you do, the spell returns to your hand as it resolves instead of going to the graveyard.",
  },
  {
    name: "Cascade",
    definition:
      "When you cast this spell, exile cards from the top of your library until you exile a nonland card that costs less. You may cast it without paying its mana cost.",
  },
  {
    name: "Casualty",
    definition:
      "As you cast this spell, you may sacrifice a creature with power at least the stated number. If you do, copy the spell.",
  },
  {
    name: "Cleave",
    definition:
      "You may cast this spell for its cleave cost. If you do, remove the words in square brackets from its text.",
  },
  {
    name: "Companion",
    definition:
      "If your starting deck meets the stated condition, this card may begin the game outside it. Once per game, you may pay {3} to put it into your hand from there.",
  },
  {
    name: "Connive",
    definition:
      "Draw a card, then discard a card. If you discarded a nonland card, put a +1/+1 counter on the conniving creature.",
  },
  {
    name: "Convoke",
    definition:
      "Your creatures can help cast this spell. Each creature you tap while casting it pays for {1} or one mana of that creature's color.",
  },
  {
    name: "Crew",
    definition:
      "Tap any number of creatures you control with total power at least the stated number: this Vehicle becomes an artifact creature until end of turn.",
  },
  {
    name: "Cycling",
    definition: "Pay the cycling cost and discard this card: draw a card.",
  },
  {
    name: "Dash",
    definition:
      "You may cast this creature for its dash cost. It gains haste and returns to your hand at the beginning of the next end step.",
  },
  {
    name: "Deathtouch",
    definition: "Any amount of damage this deals to a creature is enough to destroy it.",
  },
  {
    name: "Defender",
    definition: "This creature can't attack.",
  },
  {
    name: "Delve",
    definition:
      "Each card you exile from your graveyard while casting this spell pays for {1} of its cost.",
  },
  {
    name: "Disturb",
    definition:
      "You may cast this card transformed — using its back face — from your graveyard for its disturb cost.",
  },
  {
    name: "Double strike",
    definition:
      "This creature deals combat damage twice: once with first-strike damage and once with regular combat damage.",
  },
  {
    name: "Embalm",
    definition:
      "Pay the embalm cost and exile this card from your graveyard: create a token copy of it that's a white Zombie with no mana cost.",
  },
  {
    name: "Emerge",
    definition:
      "You may cast this spell by sacrificing a creature and paying the emerge cost reduced by that creature's mana value.",
  },
  {
    name: "Enchant",
    definition:
      "This Aura can only be attached to the kind of object named after the word, such as \"Enchant creature.\"",
  },
  {
    name: "Equip",
    definition:
      "Pay the equip cost: attach this Equipment to a creature you control. Activate only as a sorcery.",
  },
  {
    name: "Escape",
    definition:
      "You may cast this card from your graveyard by paying its escape cost, which includes exiling other cards from your graveyard.",
  },
  {
    name: "Evoke",
    definition:
      "You may cast this creature for its evoke cost. If you do, sacrifice it when it enters the battlefield — its enter trigger still happens.",
  },
  {
    name: "Exalted",
    definition:
      "Whenever a creature you control attacks alone, it gets +1/+1 until end of turn for each instance of exalted you control.",
  },
  {
    name: "Exploit",
    definition:
      "When this creature enters the battlefield, you may sacrifice a creature to get the stated effect.",
  },
  {
    name: "Explore",
    definition:
      "Reveal the top card of your library. Put it into your hand if it's a land. Otherwise, put a +1/+1 counter on the exploring creature, then keep the card on top or put it into your graveyard.",
  },
  {
    name: "Extort",
    definition:
      "Whenever you cast a spell, you may pay {W/B}. If you do, each opponent loses 1 life and you gain that much life.",
  },
  {
    name: "Fabricate",
    definition:
      "When this creature enters the battlefield, put the stated number of +1/+1 counters on it, or create that many 1/1 Servo artifact creature tokens.",
  },
  {
    name: "Fight",
    definition:
      "Each of two creatures deals damage equal to its power to the other. Neither is attacking or blocking, so combat abilities like first strike don't apply.",
  },
  {
    name: "First strike",
    definition:
      "This creature deals combat damage before creatures without first strike or double strike.",
  },
  {
    name: "Flash",
    definition: "You may cast this spell any time you could cast an instant.",
  },
  {
    name: "Flashback",
    definition:
      "You may cast this card from your graveyard for its flashback cost. Then exile it.",
  },
  {
    name: "Flying",
    definition: "This creature can't be blocked except by creatures with flying or reach.",
  },
  {
    name: "Foretell",
    definition:
      "During your turn, you may pay {2} and exile this card from your hand face down. Cast it on a later turn for its foretell cost.",
  },
  {
    name: "Goad",
    definition:
      "Until your next turn, the goaded creature attacks each combat if able, and attacks a player other than you if able.",
  },
  {
    name: "Haste",
    definition:
      "This creature can attack and use {T} abilities the turn it comes under your control.",
  },
  {
    name: "Hexproof",
    definition:
      "This permanent can't be the target of spells or abilities your opponents control.",
  },
  {
    name: "Improvise",
    definition:
      "Your artifacts can help cast this spell. Each artifact you tap while casting it pays for {1}.",
  },
  {
    name: "Indestructible",
    definition:
      "Damage and effects that say \"destroy\" don't destroy this. It can still be sacrificed, exiled, or reduced to zero toughness.",
  },
  {
    name: "Infect",
    definition:
      "This deals damage to creatures in the form of -1/-1 counters and to players in the form of poison counters. A player with ten poison counters loses the game.",
  },
  {
    name: "Investigate",
    definition:
      "Create a Clue artifact token with \"{2}, Sacrifice this artifact: Draw a card.\"",
  },
  {
    name: "Jump-start",
    definition:
      "You may cast this card from your graveyard by discarding a card in addition to its other costs. Then exile it.",
  },
  {
    name: "Kicker",
    definition:
      "You may pay the kicker cost as you cast this spell for an additional or upgraded effect.",
  },
  {
    name: "Level up",
    definition:
      "Pay the level up cost: put a level counter on this creature. Its power, toughness, and abilities improve at the printed level thresholds. Activate only as a sorcery.",
  },
  {
    name: "Lifelink",
    definition: "Damage dealt by this also causes its controller to gain that much life.",
  },
  {
    name: "Living weapon",
    definition:
      "When this Equipment enters the battlefield, create a 0/0 black Phyrexian Germ creature token, then attach the Equipment to it.",
  },
  {
    name: "Madness",
    definition:
      "If you discard this card, you may cast it for its madness cost instead of putting it into your graveyard.",
  },
  {
    name: "Menace",
    definition: "This creature can't be blocked except by two or more creatures.",
  },
  {
    name: "Mill",
    definition:
      "Put the stated number of cards from the top of the affected player's library into their graveyard.",
  },
  {
    name: "Miracle",
    definition:
      "You may cast this card for its miracle cost when you draw it, if it's the first card you've drawn this turn.",
  },
  {
    name: "Modular",
    definition:
      "This creature enters the battlefield with the stated number of +1/+1 counters. When it dies, you may move its +1/+1 counters to target artifact creature.",
  },
  {
    name: "Monstrosity",
    definition:
      "If this creature isn't monstrous, put the stated number of +1/+1 counters on it and it becomes monstrous — a one-time upgrade other abilities can check.",
  },
  {
    name: "Morph",
    definition:
      "You may cast this card face down for {3} as a 2/2 creature. Turn it face up any time for its morph cost.",
  },
  {
    name: "Mutate",
    definition:
      "You may cast this spell for its mutate cost targeting a non-Human creature you own. They merge into one creature: the top card's stats and every card's abilities.",
  },
  {
    name: "Ninjutsu",
    definition:
      "Pay the ninjutsu cost and return an unblocked attacker you control to hand: put this card from your hand onto the battlefield tapped and attacking.",
  },
  {
    name: "Overload",
    definition:
      "You may cast this spell for its overload cost. If you do, replace \"target\" in its text with \"each.\"",
  },
  {
    name: "Persist",
    definition:
      "When this creature dies, if it had no -1/-1 counters on it, return it to the battlefield with a -1/-1 counter.",
  },
  {
    name: "Phasing",
    definition:
      "This permanent phases out during its controller's untap step, then phases back in a turn later. While phased out, it's treated as though it doesn't exist.",
  },
  {
    name: "Proliferate",
    definition:
      "Choose any number of permanents and/or players with counters, then give each another counter of each kind already there.",
  },
  {
    name: "Protection",
    definition:
      "This can't be blocked, targeted, dealt damage, enchanted, or equipped by anything with the stated quality, such as \"protection from red.\"",
  },
  {
    name: "Prowess",
    definition:
      "Whenever you cast a noncreature spell, this creature gets +1/+1 until end of turn.",
  },
  {
    name: "Reach",
    definition: "This creature can block creatures with flying.",
  },
  {
    name: "Rebound",
    definition:
      "If this spell was cast from your hand, exile it as it resolves. At the beginning of your next upkeep, you may cast it from exile without paying its mana cost.",
  },
  {
    name: "Regenerate",
    definition:
      "The next time this creature would be destroyed this turn, instead tap it, remove it from combat, and remove all damage from it.",
  },
  {
    name: "Riot",
    definition:
      "This creature enters the battlefield with your choice of a +1/+1 counter or haste.",
  },
  {
    name: "Scavenge",
    definition:
      "Pay the scavenge cost and exile this card from your graveyard: put a number of +1/+1 counters equal to its power on target creature. Activate only as a sorcery.",
  },
  {
    name: "Scry",
    definition:
      "Look at the stated number of cards from the top of your library. Put any of them on the bottom and the rest back on top in any order.",
  },
  {
    name: "Shroud",
    definition:
      "This permanent can't be the target of any spells or abilities — including your own.",
  },
  {
    name: "Soulbond",
    definition:
      "You may pair this creature with another unpaired creature you control when either enters the battlefield. Both get the stated bonus while paired.",
  },
  {
    name: "Split second",
    definition:
      "While this spell is on the stack, players can't cast spells or activate abilities that aren't mana abilities.",
  },
  {
    name: "Storm",
    definition:
      "When you cast this spell, copy it for each other spell cast before it this turn.",
  },
  {
    name: "Surveil",
    definition:
      "Look at the stated number of cards from the top of your library. Put any of them into your graveyard and the rest back on top in any order.",
  },
  {
    name: "Suspend",
    definition:
      "Rather than casting this card, pay its suspend cost to exile it with the stated number of time counters. Remove one each upkeep; when the last leaves, cast it free.",
  },
  {
    name: "Toxic",
    definition:
      "A player dealt combat damage by this creature also gets the stated number of poison counters.",
  },
  {
    name: "Training",
    definition:
      "Whenever this creature attacks with another creature of greater power, put a +1/+1 counter on this creature.",
  },
  {
    name: "Trample",
    definition:
      "This creature can deal excess combat damage — beyond what its blockers can absorb — to the player or planeswalker it's attacking.",
  },
  {
    name: "Transform",
    definition:
      "Turn a double-faced permanent to its other face. Cards say when and how, such as \"transform this creature.\"",
  },
  {
    name: "Undying",
    definition:
      "When this creature dies, if it had no +1/+1 counters on it, return it to the battlefield with a +1/+1 counter.",
  },
  {
    name: "Unearth",
    definition:
      "Pay the unearth cost: return this card from your graveyard to the battlefield with haste. Exile it at end of turn or if it would leave the battlefield.",
  },
  {
    name: "Vigilance",
    definition: "Attacking doesn't cause this creature to tap.",
  },
  {
    name: "Ward",
    definition:
      "Whenever this permanent becomes the target of a spell or ability an opponent controls, counter it unless that player pays the ward cost.",
  },
  {
    name: "Wither",
    definition: "This deals damage to creatures in the form of -1/-1 counters.",
  },
];
