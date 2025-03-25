MIDISeq {

	classvar <isRunning = false;
	classvar cleanUpFunc, findPatternTypeFunc, listCmdDict;
	classvar parseToken, parseRhythm,  parseControlValuePatterns;
	classvar notePatternFunc, controlPatternFunc, stopPatternFunc, setCommandFunc, mainFunc;
	classvar controlPatternRate;
	classvar preProcessorFunc;

	*enable {
		arg midiout;

		isRunning = true;
		controlPatternRate = 16;

		cleanUpFunc = {|str|
			var outStr;
			outStr = str.replace(", ",",").replace(" ,",",").replace($;,"");
			while({outStr.contains("  ")},{outStr = outStr.replace("  ", " ")});
			if(str.beginsWith(" "), {str = str.drop(1)});
			if(str.endsWith(" "), {str = str.drop(-1)});
			outStr
		};

		findPatternTypeFunc = {|str|
			var type;
			type = case
			{"^\\w+\\.".matchRegexp(str)}{\stop}
			{str.beginsWith("set ")}{\set}
			{str.contains(" ctl ")}{\control}
			{\note};
			// type.postln;
		};

		listCmdDict = IdentityDictionary[
			\r -> Prand,
			\xr -> Pxrand,
			\shuf -> Pshuf
		];

		parseToken = {|token, repeat=inf|
			var isMultiPatterns;

			isMultiPatterns = ">\\d+".matchRegexp(token);
			if(isMultiPatterns.not,{
				var out;
				var tokenType;

				tokenType = case
				{"^(\\w)+,(\\w)+".matchRegexp(token) && ("\\w+$").matchRegexp(token)}{\arrayNoBrackets}
				{"\\w+\\[".matchRegexp(token) || "\\w+\\(".matchRegexp(token)}{\arrayWithListCmd}
				{\scExpr};

				out = switch(tokenType)

				{\arrayNoBrackets}
				{Pseq(("["++token++"]").interpret,repeat)
				}

				{\arrayWithListCmd}
				{var cmd, cmdEnds, pattern, list;
					cmdEnds = token.findAllRegexp("\\w\\[|\\w\\(")[0];
					cmd = token[0..cmdEnds].asSymbol;
					pattern = listCmdDict.at(cmd);
					if(pattern.notNil,{
						list = token.copyToEnd(cmdEnds+1).interpret;
						pattern.new(list, repeat);
					},{
						"Pattern command \"%\" not found".format(cmd).error;
					});
				}

				{\scExpr}{
					var res;
					res = token.interpret;
					if(res.isArray,{
						Pseq(res, repeat)
					},{
						Pseq([res],repeat)
					})
				};
				out;
			},{
				var repeatSigns, res;
				repeatSigns = token.findRegexp(">\\d+");
				res = repeatSigns.size.collect{|i|
					var start, end, times, out;
					start = if(i > 0, {repeatSigns[i-1][0]+repeatSigns[i-1][1].size},{0});
					end = if(i < (repeatSigns.size-1),{repeatSigns[i][0]-1},{token.size-repeatSigns[i][1].size-1});
					times = repeatSigns[i][1].drop(1).interpret;
					out = parseToken.value(token[start..end], times);
				};
				res = Pseq(res, inf);
			});

		};

		parseRhythm = {|rhythmToken, mode, repeat=inf|
			var hasDurMul, rhythmString, durMul, out;

			hasDurMul = rhythmToken.contains("||");
			if(hasDurMul,{
				rhythmString = rhythmToken.copyFromStart(rhythmToken.find("||")-1);

				durMul = rhythmToken.copyToEnd(rhythmToken.find("||") + 2).interpret;

			},{
				rhythmString = rhythmToken;
				durMul = 1;
			});

			out = switch(mode)
			{\legato}
			{
				/*if(hasDurMul,{
				rhythmString.convertRhythm * durMul;
				},{
				rhythmString.convertRhythm * durMul;
				})*/
				rhythmString.convertRhythm * durMul;
			}
			{\step}
			{
				var stepDur;
				var step, durs;
				if(rhythmString.contains($|),{
					var rhythmStrings, numSteps;
					numSteps = rhythmString.findAll($o).size + rhythmString.findAll($-).size;
					stepDur = 4 * numSteps.reciprocal * durMul;
					rhythmStrings = rhythmString.split($|);
					step = rhythmStrings.collect{|str| str.ascii.replace(111, true).replace(45, false)}.flat;
					step = Pseq(step, repeat);
					durs = rhythmStrings.collect{|str| str.ascii.replace(111, stepDur).replace(45, Rest(stepDur))}.flat;
					durs = Pseq(durs,repeat);
					[step, durs];

				},{
					stepDur = 4 * rhythmString.size.reciprocal * durMul;
					step = Pseq(rhythmString.ascii.replace(111, true).replace(45, false),repeat);
					durs = Pseq(rhythmString.ascii.replace(111,stepDur).replace(45,Rest(stepDur)),repeat);
					[step, durs];
				});


			};
			out;
		};

		parseControlValuePatterns = {|mode, paramStrings|
			var controlPattern, durPattern;
			var out;
			switch(mode)
			{\shape}
			{
				var range, shape, cyclePerBar, cycleDur;

				range = paramStrings[0].split($-);
				range = range.collect{|val| val.interpret};
				cyclePerBar =  paramStrings[2].interpret;
				cycleDur = 4 / cyclePerBar;

				shape = case
				{
					(paramStrings[1] == "straight") || (paramStrings[1] == "=")
				}
				{\straight}//linear, one shape
				{
					(paramStrings[1] == "straightSine") || (paramStrings[1] == "=~")
				}
				{\straightSine}
				{
					(paramStrings[1] == "triangle") || (paramStrings[1] == "tri") || (paramStrings[1] == "^")
				}
				{\triangle}//updown or downup, linear
				{
					(paramStrings[1] == "sine") || (paramStrings[1] == "~")
				}
				{\sine}//updown
				{
					(paramStrings[1] == "square") || (paramStrings[1] == "sqr")
				}
				{\square}
				{
					(paramStrings[1] == "rand")
				}
				{\rand}
				{
					(paramStrings[1] == "white")
				}
				{\white};
				/*		{
				("^-?\\d+(\\.\\d+)?$".matchRegexp(paramStrings[1]));
				}
				{\curve};*/
				//

				out = switch(shape)
				{\straight}
				{
					controlPattern = Pseg(Pseq(range, inf), Pseq([cycleDur, 0], inf), 'lin');
					durPattern = controlPatternRate.reciprocal;
				}
				{\straightSine}
				{
					controlPattern = Pseg(Pseq(range, inf), Pseq([cycleDur, 0], inf), 'sin');
					durPattern = controlPatternRate.reciprocal;
				}
				{\triangle}
				{
					controlPattern = Pseg(Pseq(range, inf), Pseq([cycleDur * 0.5], inf), 'lin');
					durPattern = controlPatternRate.reciprocal;
				}
				{\sine}
				{
					controlPattern = Pseg(Pseq(range, inf), Pseq([cycleDur * 0.5], inf), 'sin');
					durPattern = controlPatternRate.reciprocal;
				}
				{\square}
				{
					controlPattern = Pseq(range,inf);
					durPattern = Pseq([cycleDur * 0.5],inf);
				}
				{\rand}
				{
					controlPattern = Pseq(Array.fill(cyclePerBar,{rrand(range[0], range[1])}), inf);
					durPattern = cycleDur;
				}
				{\white}
				{
					controlPattern = Pwhite(*range);
					durPattern = cycleDur;
				};
				out = [controlPattern, durPattern];
			}
			{\continuous}
			{
				var rhythmPattern, valuePattern;
				rhythmPattern = parseRhythm.value(paramStrings[0], \legato);
				valuePattern = parseToken.value(paramStrings[1]);
				out = [rhythmPattern, valuePattern];
			}
			{\step}
			{
				var stepPattern, valuePattern;
				paramStrings[0] = paramStrings[0].drop(1);
				stepPattern = parseRhythm.value(paramStrings[0], \step);
				valuePattern = parseToken.value(paramStrings[1]);
				out = [stepPattern, valuePattern];
			};

			out;
		};

		notePatternFunc = {|codeString|
			var tokens;
			var name, channelPattern, midinotePattern, rhythmPattern, ampPattern;
			var rhythmMode;

			tokens = codeString.split($ );

			rhythmMode = if(tokens[3].beginsWith("`"),{
				tokens[3] = tokens[3].drop(1);
				\step
			},{
				\legato
			});

			name = tokens[0];
			name = "midiSeq_" ++ name;
			name = name.asSymbol;

			channelPattern = parseToken.value(tokens[1]);//a pattern
			midinotePattern = parseToken.value(tokens[2]);
			ampPattern = parseToken.value(tokens[4]);
			rhythmPattern = parseRhythm.value(tokens[3], rhythmMode);

			switch(rhythmMode)
			{\legato}
			{

				if(Pdef.all.keys.includes(name).not,{
					Pdef(name).quant = 4;
				});
				// Pdef(name).stop;
				Pdef(name,Pbind(
					'type', 'midi',
					'midiout', midiout,
					// 'midiout', m,
					'chan', channelPattern,
					'midinote', midinotePattern,
					'amp', ampPattern,
					'dur', Pseq(rhythmPattern[0], inf),
				));
				// Pdef(name).quant = quant;

			}
			{\step}
			{
				var step, durs;
				step = rhythmPattern[0];
				durs = rhythmPattern[1];

				if(Pdef.all.keys.includes(name).not,{
					Pdef(name).quant = 4;
				});

				Pdef(name,Pbind(
					'type', 'midi',
					'midiout', midiout,
					// 'midiout', m,
					'step', step,
					'chan', Pgate(channelPattern, inf, \step),
					'midinote', Pgate(midinotePattern, inf, \step),
					'amp', Pgate(ampPattern, inf, \step),
					'dur', durs,
				));

			};
			Pdef(name).play;
			"pattern %".format(name).asCompileString;
		};

		controlPatternFunc = {|codeString|
			var tokens;
			var name, channel, ctlNum, controlValuePatterns;
			var controlPattern, valuePattern, stepPattern, durPattern;
			var mode;
			var isValid;

			isValid = true;

			tokens = codeString.split($ );

			//3 modes = step mode, continuous, mode & shape mode
			mode = case
			{"^\\d+-\\d+$".matchRegexp(tokens[4])}{\shape}
			{"^(o|-)+$".matchRegexp(tokens[4])}{\continuous}
			{"^`(o|-)+$".matchRegexp(tokens[4])}{\step};

			name = tokens[0];
			name = "midiSeq_" ++ name;
			name = name.asSymbol;

			//Only single value is supported in MIDI Control Patterns
			channel = tokens[2].asInteger;
			//
			ctlNum = tokens[3].asInteger;

			//
			controlValuePatterns = parseControlValuePatterns.value(mode, tokens[4..6]);


			switch(mode)
			{\shape}
			{//controlPattern, valuePattern, stepPattern, durPattern;
				stepPattern = 0;
				controlPattern = controlValuePatterns[0];
				durPattern = controlValuePatterns[1];
			}
			{\continuous}
			{
				stepPattern = 0;
				controlPattern = Pseg(controlValuePatterns[1],controlValuePatterns[0][0], 'lin',inf);
				durPattern = Pseq([0.0625],inf);
				// d = [controlPattern, durPattern];
			}
			{\step}
			{
				stepPattern = controlValuePatterns[0][0];
				controlPattern = controlValuePatterns[1];
				durPattern = controlValuePatterns[0][1];
			};

			if(Pdef.all.keys.includes(name).not,{
				Pdef(name).stop;
				Pdef(name).quant = 4;
			});

			if(mode == \continuous,{
				Pdef(name).quant = Quant(4, controlValuePatterns[0][1],0)
			});

			Pdef(name,Pbind(
				'type', 'midi',
				'midiout', midiout,
				// 'midiout', m,
				'midicmd', 'control',
				'chan', channel,
				'ctlNum', ctlNum,
				'step', stepPattern,
				'control', controlPattern,
				'dur', durPattern
			));

			Pdef(name).play;
			"pattern %".format(name).asCompileString;
		};

		stopPatternFunc = {|str|
			var name;
			name = str.copyFromStart(str.find(".")-1);
			if(name != "all",{
				name = "midiSeq_" ++ name;
				name = name.asSymbol;
				Pdef(name).stop;
				"pattern % stopped".format(name).asCompileString;
			},{
				Pdef.all.keys.do{|name| Pdef(name).stop};
				"pattern % stopped".format(name).asCompileString;
			});


		};

		setCommandFunc = {|str|
			var tokens;
			tokens = str.split($ );
			if(tokens[1] == "bpm",{
				var tempo;
				tempo = tokens[2].interpret;
				tempo = tempo / 60;
				TempoClock.default.tempo = tempo;
				"setting bpm to %".format(tempo * 60).asCompileString;
			})
		};

		mainFunc = {
			arg code;
			var codeString, commandType;
			var out;

			codeString = cleanUpFunc.value(code);
			commandType = findPatternTypeFunc.value(codeString);


			switch(commandType)
			{\note}{notePatternFunc.value(codeString)}
			{\control}{controlPatternFunc.value(codeString)}
			{\stop}{stopPatternFunc.value(codeString)}
			{\set}{setCommandFunc.value(codeString)}
		};

		Platform.case(
			\osx,{
				preProcessorFunc = {|code|
					var documentString, escapeString, escapeRanges, inRange, hasEscape, currentPos;
					escapeString = "@@@";
					documentString = Document.current.string;
					currentPos = Document.current.selectionStart;
					hasEscape = "(\\R|^)@@@\\R+.*\\R+@@@".matchRegexp(documentString);
					if(hasEscape,{
						escapeRanges = Document.current.string.findAll("@@@");
						escapeRanges = (escapeRanges.size/2).asInteger.collect{|i| [escapeRanges[i*2], escapeRanges[i*2+1]]};
						inRange = false;
						escapeRanges.do{|range, i|
							if((currentPos > (range[0]+3)) && (currentPos < range[1]),{
								inRange = true;
							});
						};
					});
					if(hasEscape && inRange,{
						mainFunc.value(code);
					},{
						code
					});
				};

				thisProcess.interpreter.preProcessor = preProcessorFunc;

		});
		"MIDISeq enabled".postln;

	}//end of enable


	* disable {
		thisProcess.interpreter.preProcessor = nil;
	}

	*midiseqCmd{
		arg str;
		if (isRunning,{
			mainFunc.value(str);
		},{
			^"MIDISeq is not enabled".error;
		})
	}

}


+String {
	asMIDISeq{
		if (MIDISeq.isRunning.postln,{
			^MIDISeq.midiseqCmd(this);
		},{
			^"MIDISeq is not enabled".error;
		})
	}

	convertRhythm {
		arg onChar=$o, offChar=$-, numBeats=4, normalize=true, useRestForOffset=true;
		var str, result, offset, rest;
		if(this.contains($|).not,{
			result = Array(64);
			offset = this.find(onChar);

			if(offset.notNil,{
				if(offset > 0, {
					if(useRestForOffset,{
						rest = Rest(offset);
						// offset = 0;
						str = this.copy;
					},{
						str = this.rotate(offset*(-1));
					});
				},{
					str = this.copy;
				});

				result = str.findAll(onChar);
				result = result.differentiate.drop(1)++(str.size-result.last);

				if(useRestForOffset,{
					if(normalize,{
						if(offset > 0,{
							result = result.insert(0, offset);
							result = result.normalizeSum*numBeats;
							result[0] = Rest(result[0]);
						},{
							result = result.normalizeSum*numBeats;
						});
						// ^[result, offset*result.sum.value.reciprocal*numBeats]
						^[result, 0]
					},{
						if(offset > 0,{
							result = result.insert(0, offset);
							result[0] = Rest(result[0]);
						});
						// ^[result, offset]
						^[result, 0]
					});
				},{
					if(normalize,{
						^[result.normalizeSum*numBeats, offset*result.sum.reciprocal*numBeats]
					},{
						^[result, offset]
					});
				});

			},{
				^nil;
			})

		},{
			var bars;
			bars = this.split($|).collect{|bar| bar.convertRhythm};
			result = bars.collect{|barRhythm, i|
				var tempArray;
				var tempOffset;
				tempArray = barRhythm[0];
				tempOffset = tempArray.last;
				tempArray[tempArray.size-1] = tempArray.last - barRhythm[1] + bars[i+1%bars.size][1];
				// tempArray.postln;
			};
			^[result.flat,bars[0][1]];
		})
	}
}
