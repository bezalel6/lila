var puzzles = db.puzzle;
var count = 0;
puzzles.find({_id:{$gt:61000}}).forEach(function(p) {
  var parts = p.fen.split(/\s/);
  var pieceCount = parts[0].split(/[nbrqkp]/i).length - 1;
  if (pieceCount < 9) {
    count++;
    puzzles.update({
      _id: p._id
    }, {
      $set: {
        vote_backup: p.vote,
        vote: {
          up: NumberInt(0),
          down: NumberInt(0),
          sum: NumberInt(-9000)
        }
      }
    });
  }
});
print("Disabled " + count + " puzzles");
