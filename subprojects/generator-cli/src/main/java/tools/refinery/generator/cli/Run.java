package tools.refinery.generator.cli;

import tools.refinery.generator.standalone.StandaloneRefinery;

import java.io.IOException;

public class Run {
	public static void main(String[] args) throws IOException {
		var problem = StandaloneRefinery.getProblemLoader().loadString("""
				import builtin::view.
				import builtin::annotations.

				#pred objective().
				#pred crossover().
				#pred violationCount().

				@color(hex="#beaed4")
				abstract class SocialBeing {
				 	@crossover
					SocialBeing[] friend
				}
				@color(hex="#377eb8")
				class Person extends SocialBeing {
				 	@crossover
				    Dog[] pet opposite owner
				}
				@color(hex="#e5c494")
				class Dog extends SocialBeing {
				 	@crossover
				    Person owner opposite pet
				}

				% Noone should be their own friends
				propagation rule noSelf(a) ==> !friend(a,a).

				% A pet and it's owner should be true friends
				pred trueFriends(SocialBeing sb1, SocialBeing sb2) <->
				    friend(sb1, sb2),
				    friend(sb2, sb1).

				pred ownerAndPetAreNotFriends(Person p, Dog d) <->
				    pet(p, d),
				    !trueFriends(p, d).

				@violationCount
				int violation1() =
				    count{selfFriend(_)}.

				% @objective
				% int countViolationNumber() =
				%     10 - count{node(_)}.

				scope node = 10..15.
            """);
		try (var generator = StandaloneRefinery.getGeneratorFactory().createGenerator(problem)) {
			generator.generate();
			// Code can be ran from here as well
		}
	}
}
